package me.bechberger.testorder.changes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderLogger;
import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * Change detection for native source roots (C/C++/headers), mirroring
 * {@link ChangeDetector} but keyed by <b>file paths</b> instead of FQCNs.
 *
 * <p>
 * Unit key convention (shared with the gcov-artifact parser and the JNI export
 * scanner): a native unit is the path of the source file <b>relative to the
 * project root</b>, forward slashes, e.g. {@code src/profiling/foo.cpp}. All
 * consumers (index deps, coverage artifacts, change detection) must use the
 * same key or edges will not match. Files outside the project root fall back to
 * their absolute path as key.
 *
 * <p>
 * Supports the same modes as {@link ChangeDetector}; {@code auto} must be
 * resolved by the caller first (same contract as
 * {@link ChangeDetectionSupport#resolveMode(String, Path)}). EXPLICIT mode
 * returns the explicit comma-separated path list, if any — otherwise empty.
 */
public final class NativeChangeDetector {

	/** Native source file extensions tracked by native change detection. */
	public static final Set<String> NATIVE_EXTENSIONS = Set.of(".cpp", ".cc", ".c", ".cxx", ".c++", ".hpp", ".hh", ".h",
			".hxx");

	private NativeChangeDetector() {
	}

	/** True when the path ends with a tracked native extension. */
	public static boolean isNativeSourceFile(String path) {
		if (path == null) {
			return false;
		}
		String lower = path.toLowerCase(Locale.ROOT);
		return NATIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
	}

	/**
	 * Detects changed native files and updates the hash snapshot for SINCE_LAST_RUN
	 * mode.
	 *
	 * @param mode
	 *            resolved change mode (never {@code AUTO})
	 * @param projectRoot
	 *            project root; unit keys are relative to this directory
	 * @param nativeRoots
	 *            declared native source roots (absolute or project-relative)
	 * @param hashFile
	 *            native hash snapshot file
	 * @param explicitPaths
	 *            explicit comma/semicolon-separated changed native paths; when
	 *            non-blank, detection is skipped entirely (same precedence as
	 *            {@code changedClasses} in {@link ChangeDetector})
	 * @param readOnly
	 *            {@code true} to avoid updating the hash snapshot
	 * @return set of changed native file paths (projectRoot-relative, forward
	 *         slashes)
	 */
	public static Set<String> detect(ChangeDetector.Mode mode, Path projectRoot, List<Path> nativeRoots, Path hashFile,
			String explicitPaths, boolean readOnly) throws IOException {
		return detect(mode, projectRoot, nativeRoots, hashFile, explicitPaths, readOnly, null);
	}

	/**
	 * As {@link #detect(ChangeDetector.Mode, Path, List, Path, String, boolean)}
	 * with a shared repo-level git listing snapshot; git-based modes filter the
	 * snapshot's cached listings in-memory when it belongs to the same git root.
	 */
	public static Set<String> detect(ChangeDetector.Mode mode, Path projectRoot, List<Path> nativeRoots, Path hashFile,
			String explicitPaths, boolean readOnly, RepoChangeListings.Snapshot shared) throws IOException {
		Objects.requireNonNull(mode);
		// Explicit paths always take precedence, mirroring explicit changedClasses.
		if (explicitPaths != null && !explicitPaths.isBlank()) {
			Path absoluteRoot = projectRoot.toAbsolutePath().normalize();
			Set<String> normalized = new LinkedHashSet<>();
			for (String entry : ChangeDetectionSupport.parseExplicitClasses(explicitPaths)) {
				String portableEntry = entry.replace('\\', '/');
				try {
					Path path = Path.of(portableEntry);
					Path absolute = path.isAbsolute() ? path : absoluteRoot.resolve(path);
					normalized.add(nativeKey(absoluteRoot, absolute));
				} catch (InvalidPathException e) {
					// Preserve malformed explicit values rather than silently dropping an override.
					normalized.add(portableEntry);
				}
			}
			return normalized;
		}
		if (nativeRoots == null || nativeRoots.isEmpty()) {
			return Set.of();
		}
		if (mode == ChangeDetector.Mode.EXPLICIT) {
			return Set.of();
		}
		Path absoluteRoot = projectRoot.toAbsolutePath().normalize();
		return switch (mode) {
			case SINCE_LAST_RUN -> detectSinceLastRun(absoluteRoot, nativeRoots, hashFile, !readOnly);
			case SINCE_LAST_COMMIT, UNCOMMITTED -> detectGit(mode, absoluteRoot, nativeRoots, shared);
			case EXPLICIT -> Set.of();
		};
	}

	// ── since-last-run ────────────────────────────────────────────────

	/**
	 * Snapshots the declared native source roots into {@code hashFile} (used by the
	 * plugins' snapshot step so SINCE_LAST_RUN detection has a baseline).
	 */
	public static void snapshot(Path projectRoot, List<Path> nativeRoots, Path hashFile) throws IOException {
		if (nativeRoots == null || nativeRoots.isEmpty() || hashFile == null) {
			return;
		}
		Map<String, NativeHashEntry> previous = Files.exists(hashFile) ? loadNativeHashes(hashFile) : Map.of();
		Map<String, NativeHashEntry> current = scanNativeRootEntries(projectRoot.toAbsolutePath().normalize(),
				nativeRoots, previous);
		saveNativeHashes(current, hashFile);
	}

	private static Set<String> detectSinceLastRun(Path projectRoot, List<Path> nativeRoots, Path hashFile,
			boolean updateSnapshot) throws IOException {
		if (hashFile == null) {
			throw new IOException("since-last-run change detection requires a native hash file path");
		}
		Map<String, NativeHashEntry> previous = Files.exists(hashFile) ? loadNativeHashes(hashFile) : Map.of();
		Map<String, NativeHashEntry> current = scanNativeRootEntries(projectRoot, nativeRoots, previous);
		Map<String, String> currentHashes = hashesOf(current);
		Set<String> changed;
		if (!previous.isEmpty() || Files.exists(hashFile)) {
			changed = diffHashes(hashesOf(previous), currentHashes);
		} else {
			// no previous snapshot → all files count as changed
			changed = current.keySet();
		}
		if (updateSnapshot) {
			saveNativeHashes(current, hashFile);
		}
		return changed;
	}

	/**
	 * Hashes all tracked native files under the declared roots. Keys are
	 * projectRoot-relative forward-slash paths.
	 */
	public static Map<String, String> scanNativeRoots(Path projectRoot, List<Path> nativeRoots) {
		return hashesOf(scanNativeRootEntries(projectRoot.toAbsolutePath().normalize(), nativeRoots, Map.of()));
	}

	private static Map<String, NativeHashEntry> scanNativeRootEntries(Path projectRoot, List<Path> nativeRoots,
			Map<String, NativeHashEntry> previous) {
		Map<String, NativeHashEntry> entries = new TreeMap<>();
		if (nativeRoots == null) {
			return entries;
		}
		for (Path configuredRoot : nativeRoots) {
			if (configuredRoot == null) {
				continue;
			}
			Path root = configuredRoot.isAbsolute() ? configuredRoot : projectRoot.resolve(configuredRoot);
			if (!Files.isDirectory(root)) {
				continue;
			}
			// Walk the real target: Files.walk does not descend into a symlinked
			// root. Keys stay in the declared-root form used by other native consumers.
			Path walkRoot;
			try {
				walkRoot = root.toRealPath();
			} catch (IOException e) {
				walkRoot = root.toAbsolutePath().normalize();
			}
			List<Path> files;
			try (Stream<Path> walk = Files.walk(walkRoot)) {
				files = walk.filter(p -> Files.isRegularFile(p) && isNativeSourceFile(p.getFileName().toString()))
						.toList();
			} catch (IOException e) {
				TestOrderLogger.warn("Failed to scan native source root {}: {}", root, e.getMessage());
				continue;
			}
			for (Path file : files) {
				String key = nativeKey(projectRoot, root.resolve(walkRoot.relativize(file)));
				BasicFileAttributes attributes = null;
				try {
					attributes = Files.readAttributes(file, BasicFileAttributes.class);
				} catch (IOException ignored) {
					// Hash anyway; unavailable metadata can never justify reusing a hash.
				}
				NativeHashEntry old = previous.get(key);
				if (attributes != null && old != null && old.size() != null && old.modified() != null
						&& old.size() == attributes.size() && old.modified().equals(attributes.lastModifiedTime())) {
					entries.put(key, old);
				} else {
					entries.put(key,
							new NativeHashEntry(FileHashStore.sha256RawSafe(file),
									attributes == null ? null : attributes.size(),
									attributes == null ? null : attributes.lastModifiedTime()));
				}
			}
		}
		return entries;
	}

	private static Map<String, String> hashesOf(Map<String, NativeHashEntry> entries) {
		Map<String, String> hashes = new TreeMap<>();
		entries.forEach((key, entry) -> hashes.put(key, entry.hash()));
		return hashes;
	}

	/**
	 * Maps a file to its unit key: path relative to the project root with forward
	 * slashes; absolute path when outside the project root.
	 */
	public static String nativeKey(Path projectRoot, Path file) {
		Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
		Path absolute = file.toAbsolutePath().normalize();
		try {
			String relative = normalizedRoot.relativize(absolute).toString().replace('\\', '/');
			if (!relative.startsWith("../")) {
				return relative;
			}
		} catch (IllegalArgumentException e) {
			// different root (e.g. declared source root outside the project)
		}
		return absolute.toString().replace('\\', '/');
	}

	/**
	 * Whether a native unit key is an absolute Unix or forward-slash drive path.
	 */
	public static boolean isAbsoluteNativeKey(String key) {
		return key.startsWith("/") || key.matches("^[A-Za-z]:/.*");
	}

	/** Whether a dependency value contains a forward-slash path separator. */
	public static boolean looksPathLike(String key) {
		return key.indexOf('/') >= 0;
	}

	private static Set<String> diffHashes(Map<String, String> previous, Map<String, String> current) {
		Set<String> changed = new TreeSet<>();
		current.forEach((key, hash) -> {
			if (!hash.equals(previous.get(key))) {
				changed.add(key);
			}
		});
		previous.forEach((key, hash) -> {
			if (!current.containsKey(key)) {
				changed.add(key);
			}
		});
		return changed;
	}

	private static final String NATIVE_HASH_SNAPSHOT_VERSION = "#native-hash-snapshot-v2";

	private static Map<String, NativeHashEntry> loadNativeHashes(Path hashFile) throws IOException {
		Map<String, NativeHashEntry> entries = new TreeMap<>();
		Path loadPath = PersistenceSupport.resolveLoadPath(hashFile);
		try (LZ4FrameInputStream lz4is = LZ4Support.frameInputStream(Files.newInputStream(loadPath));
				BufferedReader reader = new BufferedReader(new InputStreamReader(lz4is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.equals(NATIVE_HASH_SNAPSHOT_VERSION)) {
					continue;
				}
				String[] columns = line.split("\\t", -1);
				if (columns.length < 2 || columns[0].isEmpty()) {
					continue;
				}
				Long size = null;
				FileTime modified = null;
				if (columns.length >= 5 && !columns[2].isEmpty() && !columns[3].isEmpty() && !columns[4].isEmpty()) {
					try {
						size = Long.parseLong(columns[2]);
						long seconds = Long.parseLong(columns[3]);
						long nanos = Long.parseLong(columns[4]);
						modified = FileTime.from(Instant.ofEpochSecond(seconds, nanos));
					} catch (RuntimeException ignored) {
						size = null;
						modified = null;
					}
				}
				entries.put(columns[0].replace('\\', '/'), new NativeHashEntry(columns[1], size, modified));
			}
		}
		return entries;
	}

	@FunctionalInterface
	interface HashOutputStreamFactory {
		OutputStream open(Path path) throws IOException;
	}

	/** Keeps the injected stream seam for snapshot-publication failure tests. */
	static void saveHashes(Map<String, String> hashes, Path hashFile, HashOutputStreamFactory outputFactory)
			throws IOException {
		Map<String, NativeHashEntry> entries = new TreeMap<>();
		hashes.forEach((key, hash) -> entries.put(key, new NativeHashEntry(hash, null, null)));
		saveNativeHashes(entries, hashFile, outputFactory);
	}

	private static void saveNativeHashes(Map<String, NativeHashEntry> hashes, Path hashFile) throws IOException {
		saveNativeHashes(hashes, hashFile, Files::newOutputStream);
	}

	private static void saveNativeHashes(Map<String, NativeHashEntry> hashes, Path hashFile,
			HashOutputStreamFactory outputFactory) throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporaryFile = PersistenceSupport.temporarySibling(hashFile);
		boolean published = false;
		try {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					LZ4Support.frameOutputStreamHC(outputFactory.open(temporaryFile)), StandardCharsets.UTF_8))) {
				writer.write(NATIVE_HASH_SNAPSHOT_VERSION);
				writer.write('\n');
				for (Map.Entry<String, NativeHashEntry> entry : hashes.entrySet().stream()
						.sorted(Map.Entry.comparingByKey()).toList()) {
					NativeHashEntry value = entry.getValue();
					writer.write(entry.getKey());
					writer.write('\t');
					writer.write(value.hash());
					writer.write('\t');
					if (value.size() != null && value.modified() != null) {
						Instant modified = value.modified().toInstant();
						writer.write(Long.toString(value.size()));
						writer.write('\t');
						writer.write(Long.toString(modified.getEpochSecond()));
						writer.write('\t');
						writer.write(Integer.toString(modified.getNano()));
					}
					writer.write('\n');
				}
			}
			PersistenceSupport.moveIntoPlace(temporaryFile, hashFile);
			published = true;
		} finally {
			if (!published) {
				Files.deleteIfExists(temporaryFile);
			}
		}
	}

	private record NativeHashEntry(String hash, Long size, FileTime modified) {
	}

	// ── git modes ─────────────────────────────────────────────────────

	private static Set<String> detectGit(ChangeDetector.Mode mode, Path projectRoot, List<Path> nativeRoots,
			RepoChangeListings.Snapshot shared) throws IOException {
		Path gitRoot = ChangeDetector.findGitRoot(projectRoot);
		List<RootPrefix> roots = new ArrayList<>();
		for (Path configuredRoot : nativeRoots) {
			if (configuredRoot == null) {
				continue;
			}
			Path declaredRoot = configuredRoot.isAbsolute() ? configuredRoot : projectRoot.resolve(configuredRoot);
			if (Files.isDirectory(declaredRoot)) {
				roots.add(new RootPrefix(declaredRoot, ChangeDetector.toGitPrefix(projectRoot, declaredRoot, gitRoot)));
			}
		}
		if (roots.isEmpty()) {
			return Set.of();
		}

		// Obtain one repo-wide path listing, regardless of the number of declared
		// native roots, then filter that immutable set in memory.
		Set<String> gitPaths = GitPathListings.changedPaths(gitRoot, mode, shared,
				NativeChangeDetector::isNativeSourceFile);
		Set<String> changed = new TreeSet<>();
		for (RootPrefix root : roots) {
			for (String path : gitPaths) {
				if (path.startsWith(root.gitPrefix())) {
					// Key from the declared root (not its canonical symlink target) so git,
					// snapshot, coverage, and JNI keys use the same project-relative form.
					String suffix = path.substring(root.gitPrefix().length());
					changed.add(nativeKey(projectRoot, root.declaredRoot().resolve(suffix)));
				}
			}
		}
		return changed;
	}

	private record RootPrefix(Path declaredRoot, String gitPrefix) {
	}
}
