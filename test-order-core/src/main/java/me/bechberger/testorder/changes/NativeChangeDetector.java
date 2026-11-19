package me.bechberger.testorder.changes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderLogger;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

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
			return ChangeDetectionSupport.parseExplicitClasses(explicitPaths);
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

	/**
	 * Detects changed native files without updating the hash snapshot (read-only
	 * queries).
	 */
	public static Set<String> detectReadOnly(ChangeDetector.Mode mode, Path projectRoot, List<Path> nativeRoots,
			Path hashFile, String explicitPaths) throws IOException {
		return detect(mode, projectRoot, nativeRoots, hashFile, explicitPaths, true);
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
		saveHashes(scanNativeRoots(projectRoot.toAbsolutePath().normalize(), nativeRoots), hashFile);
	}

	private static Set<String> detectSinceLastRun(Path projectRoot, List<Path> nativeRoots, Path hashFile,
			boolean updateSnapshot) throws IOException {
		if (hashFile == null) {
			throw new IOException("since-last-run change detection requires a native hash file path");
		}
		Map<String, String> current = scanNativeRoots(projectRoot, nativeRoots);
		Set<String> changed;
		if (Files.exists(hashFile)) {
			FileHashStore previous = FileHashStore.load(hashFile);
			changed = diffHashes(previous.getHashes(), current);
		} else {
			// no previous snapshot → all files count as changed
			changed = current.keySet();
		}
		if (updateSnapshot) {
			saveHashes(current, hashFile);
		}
		return changed;
	}

	/**
	 * Hashes all tracked native files under the declared roots. Keys are
	 * projectRoot-relative forward-slash paths.
	 */
	public static Map<String, String> scanNativeRoots(Path projectRoot, List<Path> nativeRoots) {
		Map<String, String> hashes = new TreeMap<>();
		for (Path root : nativeRoots) {
			if (root == null || !Files.isDirectory(root)) {
				continue;
			}
			// Walk the real target: Files.walk does not descend into a symlinked
			// root. Keys stay in the DECLARED-root form so they match the gcov
			// artifacts, the JNI scanner, and git-mode detection.
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
				hashes.merge(nativeKey(projectRoot, root.resolve(walkRoot.relativize(file))),
						FileHashStore.sha256Safe(file), (a, b) -> b);
			}
		}
		return hashes;
	}

	/**
	 * Maps a file to its unit key: path relative to the project root with forward
	 * slashes; absolute path when outside the project root.
	 */
	public static String nativeKey(Path projectRoot, Path file) {
		Path absolute = file.toAbsolutePath().normalize();
		try {
			String relative = projectRoot.relativize(absolute).toString().replace('\\', '/');
			if (!relative.startsWith("../")) {
				return relative;
			}
		} catch (IllegalArgumentException e) {
			// different root (e.g. declared source root outside the project)
		}
		return absolute.toString().replace('\\', '/');
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

	private static void saveHashes(Map<String, String> hashes, Path hashFile) throws IOException {
		saveHashes(hashes, hashFile, Files::newOutputStream);
	}

	@FunctionalInterface
	interface HashOutputStreamFactory {
		OutputStream open(Path path) throws IOException;
	}

	static void saveHashes(Map<String, String> hashes, Path hashFile, HashOutputStreamFactory outputFactory)
			throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporaryFile = PersistenceSupport.temporarySibling(hashFile);
		boolean published = false;
		try {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					LZ4Support.frameOutputStreamHC(outputFactory.open(temporaryFile)), StandardCharsets.UTF_8))) {
				for (Map.Entry<String, String> entry : hashes.entrySet().stream().sorted(Map.Entry.comparingByKey())
						.toList()) {
					writer.write(entry.getKey());
					writer.write('\t');
					writer.write(entry.getValue());
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

	// ── git modes ─────────────────────────────────────────────────────

	private static Set<String> detectGit(ChangeDetector.Mode mode, Path projectRoot, List<Path> nativeRoots,
			RepoChangeListings.Snapshot shared) throws IOException {
		Path gitRoot = ChangeDetector.findGitRoot(projectRoot);
		Set<String> changed = new TreeSet<>();
		for (Path root : nativeRoots) {
			if (root == null || !Files.isDirectory(root)) {
				continue;
			}
			String prefix = ChangeDetector.toGitPrefix(projectRoot, root, gitRoot);
			Set<String> files = switch (mode) {
				case UNCOMMITTED -> gitChangedFiles(gitRoot, "status", prefix, shared);
				case SINCE_LAST_COMMIT -> gitChangedFiles(gitRoot, "diff", prefix, shared);
				default -> throw new AssertionError(mode);
			};
			for (String file : files) {
				if (!isNativeSourceFile(file) || !file.startsWith(prefix)) {
					continue;
				}
				// Key from the DECLARED root (the same form scanNativeRoots, the JNI
				// scanner, and gcov artifacts use) — never from the canonical real
				// path: toGitPrefix canonicalizes symlinked roots, so a real-path key
				// would diverge from the declared-root keys of every other mode.
				changed.add(nativeKey(projectRoot, root.resolve(file.substring(prefix.length()))));
			}
		}
		return changed;
	}

	/**
	 * Changed file paths (git-root-relative) for one root prefix. Uses the shared
	 * repo-level listing snapshot when it belongs to the same git root; otherwise
	 * spawns scoped git commands. SINCE_LAST_COMMIT falls back to tracked-file
	 * listing when HEAD~1 is unavailable (mirroring {@link GitChangeDetector}).
	 */
	private static Set<String> gitChangedFiles(Path gitRoot, String kind, String prefix,
			RepoChangeListings.Snapshot shared) throws IOException {
		if (matchesGitRoot(gitRoot, shared)) {
			try {
				Collection<String> paths = switch (kind) {
					case "status" -> shared.statusPorcelainPaths();
					case "diff" -> {
						RepoChangeListings.LastCommitData lastCommit = shared.lastCommit();
						yield lastCommit.hasHeadParent && !lastCommit.diffFailed
								? lastCommit.diffPaths
								: shared.trackedPaths();
					}
					default -> throw new AssertionError(kind);
				};
				return filterByPrefix(paths, prefix);
			} catch (IOException | RuntimeException e) {
				// fail-safe: any snapshot problem falls back to the direct git path
			}
		}
		return switch (kind) {
			case "status" -> gitStatusPaths(gitRoot, prefix);
			case "diff" -> gitDiffPaths(gitRoot, prefix);
			default -> throw new AssertionError(kind);
		};
	}

	/**
	 * Mirrors
	 * {@link GitChangeDetector#matchesGitRoot(Path, RepoChangeListings.Snapshot)}.
	 */
	private static boolean matchesGitRoot(Path gitRoot, RepoChangeListings.Snapshot shared) {
		if (shared == null) {
			return false;
		}
		try {
			return shared.gitRoot().equals(gitRoot.toRealPath());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static Set<String> filterByPrefix(Collection<String> paths, String prefix) {
		Set<String> out = new TreeSet<>();
		for (String p : paths) {
			String selected = p;
			// git status --porcelain may report renames as "old -> new"
			int arrow = selected.indexOf(" -> ");
			if (arrow >= 0) {
				selected = selected.substring(arrow + 4);
			}
			String normalized = RepoChangeListings.unquoteGitPath(selected).replace('\\', '/');
			if (normalized.startsWith(prefix) && isNativeSourceFile(normalized)) {
				out.add(normalized);
			}
		}
		return out;
	}

	private static Set<String> gitStatusPaths(Path gitRoot, String prefix) throws IOException {
		List<String> lines = runGit(gitRoot, "status", "--porcelain");
		// porcelain format: XY <path>; renames appear as "R old -> new"
		List<String> paths = new ArrayList<>();
		for (String line : lines) {
			if (line.length() > 3) {
				paths.add(line.substring(3));
			}
		}
		return filterByPrefix(paths, prefix);
	}

	private static Set<String> gitDiffPaths(Path gitRoot, String prefix) throws IOException {
		List<String> lines;
		try {
			lines = runGit(gitRoot, "diff", "--name-only", "HEAD~1", "HEAD", "--", prefix);
		} catch (IOException e) {
			// HEAD~1 unavailable — mirror GitChangeDetector: treat all tracked files
			// under the prefix as changed
			TestOrderLogger.warn("git revision HEAD~1 is unavailable; treating all tracked native files as changed");
			lines = runGit(gitRoot, "ls-files", "--", prefix);
		}
		return filterByPrefix(lines, prefix);
	}

	private static List<String> runGit(Path dir, String... args) throws IOException {
		return GitSupport.runGit(dir, true, args);
	}
}
