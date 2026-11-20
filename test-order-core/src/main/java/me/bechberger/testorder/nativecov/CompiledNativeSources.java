package me.bechberger.testorder.nativecov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import me.bechberger.testorder.changes.NativeChangeDetector;

/**
 * Resolves the set of native source files that were actually compiled into the
 * declared {@code nativeTests.binaries} build outputs, for use as a scoping
 * filter over the JNI export scan and gcov coverage parsing.
 *
 * <p>
 * The declared value may be a directory (the supported form — walked
 * recursively for gcov notes files) or a single binary file (best-effort
 * same-stem {@code .gcno} sibling lookup). Every gcov notes file embeds the
 * absolute compile-time path of its source; because the format's version word
 * varies across GCC and clang releases, extraction is deliberately tolerant:
 * the file bytes are scanned for printable runs containing a tracked native
 * extension rather than struct-parsed, so a format revision cannot break it.
 *
 * <p>
 * Degradation contract: an empty result (no binaries, no notes files, or
 * nothing parseable) disables scoping entirely — callers must fall back to the
 * unscoped behavior. Scoping is subtractive only: it can drop false positives
 * (sources that were never compiled into the tested build), never add or miss
 * edges for sources that were.
 */
public final class CompiledNativeSources {

	/**
	 * Files larger than this are skipped (a .gcno over 16 MB is not a notes file).
	 */
	static final int MAX_GCNO_BYTES = 16 * 1024 * 1024;

	/**
	 * Matches a path-like printable run ending in a tracked native extension.
	 * Applied over maximal printable substrings of the raw bytes; a candidate like
	 * {@code build/obj/foo.cpp.o} therefore yields {@code foo.cpp}.
	 */
	private static final Pattern SOURCE_PATH = Pattern
			.compile("[A-Za-z0-9_.\\-/\\\\~ ]+?\\.(?:cpp|cc|cxx|c\\+\\+|hpp|hh|hxx|h|c)\\b");

	private CompiledNativeSources() {
	}

	/**
	 * Resolves the compiled native source files as forward-slash unit keys relative
	 * to {@code keyBase}, restricted to sources under one of {@code roots} (the
	 * tracked set). Keys outside {@code keyBase} cannot be relativized and are
	 * skipped, mirroring the per-project unit-key contract.
	 *
	 * @return the project-relative keys; empty when nothing could be resolved
	 *         (callers treat empty as "scoping disabled")
	 */
	public static Set<String> compiledSourceKeys(List<Path> binaries, Path keyBase, List<Path> roots)
			throws IOException {
		Set<String> keys = new TreeSet<>();
		if (binaries == null || binaries.isEmpty() || keyBase == null || roots == null || roots.isEmpty()) {
			return keys;
		}
		Path lexicalBase = keyBase.toAbsolutePath().normalize();
		// Canonical forms are used only for containment. Keep the lexical base
		// separately so emitted keys match NativeChangeDetector.nativeKey.
		Path canonicalBase = realOrNormalized(lexicalBase);
		List<Path> canonicalRoots = new ArrayList<>();
		for (Path root : roots) {
			if (root != null) {
				Path absoluteRoot = root.isAbsolute() ? root : lexicalBase.resolve(root);
				canonicalRoots.add(realOrNormalized(absoluteRoot));
			}
		}
		ResolutionContext context = new ResolutionContext(lexicalBase, canonicalBase, canonicalRoots);
		for (Path binary : binaries) {
			for (Path notes : notesFilesFor(binary)) {
				keys.addAll(sourceKeysInNotes(notes, context));
			}
		}
		return keys;
	}

	/** Holds per-invocation lexical keys and cached canonical containment paths. */
	private static final class ResolutionContext {
		private final Path lexicalBase;
		private final Path canonicalBase;
		private final List<Path> canonicalRoots;
		private final Map<Path, Path> canonicalCandidates = new HashMap<>();

		private ResolutionContext(Path lexicalBase, Path canonicalBase, List<Path> canonicalRoots) {
			this.lexicalBase = lexicalBase;
			this.canonicalBase = canonicalBase;
			this.canonicalRoots = List.copyOf(canonicalRoots);
		}

		private Path canonicalCandidate(Path candidate) {
			Path normalized = candidate.toAbsolutePath().normalize();
			return canonicalCandidates.computeIfAbsent(normalized, CompiledNativeSources::realOrNormalized);
		}

		private Path canonicalBase() {
			return canonicalBase;
		}

		private List<Path> canonicalRoots() {
			return canonicalRoots;
		}
	}

	private static Path realOrNormalized(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		// Canonicalize through symlinks (/var vs /private/var on macOS). The
		// candidate may not exist on disk (a notes file may reference a source
		// that was since deleted), so fall back to the nearest existing ancestor
		// and append the remainder.
		Path p = normalized;
		while (p != null) {
			try {
				Path real = p.toRealPath();
				return p == normalized ? real : real.resolve(p.relativize(normalized));
			} catch (IOException e) {
				p = p.getParent();
			}
		}
		return normalized;
	}

	/**
	 * Notes files for one declared binary: a directory is walked recursively for
	 * {@code *.gcno}; a file gets a best-effort same-stem sibling lookup
	 * ({@code libx.so} → {@code libx.gcno}), which only works when the build places
	 * notes next to the linked binary.
	 */
	private static List<Path> notesFilesFor(Path binary) throws IOException {
		List<Path> notes = new ArrayList<>();
		if (!Files.exists(binary)) {
			return notes;
		}
		if (Files.isDirectory(binary)) {
			try (Stream<Path> walk = Files.walk(binary)) {
				walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".gcno"))
						.forEach(notes::add);
			}
			return notes;
		}
		String name = binary.getFileName().toString();
		int dot = name.lastIndexOf('.');
		Path sibling = binary.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".gcno");
		if (Files.isRegularFile(sibling)) {
			notes.add(sibling);
		}
		return notes;
	}

	/**
	 * Extracts the source paths embedded in one notes file and keys them relative
	 * to {@code base}. Absolute compile-time paths are relativized when they lie
	 * under {@code base}; relative paths are resolved against both the notes file's
	 * directory and {@code base} (build systems record either, depending on the
	 * invocation directory).
	 */
	private static Set<String> sourceKeysInNotes(Path notes, ResolutionContext context) throws IOException {
		Set<String> keys = new TreeSet<>();
		if (Files.size(notes) > MAX_GCNO_BYTES) {
			return keys;
		}
		byte[] bytes = Files.readAllBytes(notes);
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		Matcher matcher = SOURCE_PATH.matcher(text);
		while (matcher.find()) {
			for (Path candidate : resolveCandidate(matcher.group().trim(), notes, context.lexicalBase)) {
				if (isUnderRoots(candidate, context)) {
					// Emit the key relative to the CANONICAL base: build systems record
					// absolute paths under either form of a symlinked base (macOS /var
					// vs /private/var), so only canonical-vs-canonical containment and
					// relativization match every recording. The resulting relative key
					// is identical to the lexical form when no symlink is involved, so
					// the keys still match NativeChangeDetector.nativeKey and the index.
					keys.add(relativizeForward(candidate, context.canonicalBase()));
				}
			}
		}
		return keys;
	}

	/**
	 * Candidate absolute lexical paths for one extracted path: the absolute path,
	 * or both relative interpretations (notes directory and key base).
	 */
	private static List<Path> resolveCandidate(String raw, Path notes, Path lexicalBase) {
		List<Path> candidates = new ArrayList<>();
		String normalized = raw.replace('\\', '/');
		Path path = Path.of(normalized);
		if (path.isAbsolute()) {
			candidates.add(path.toAbsolutePath().normalize());
			return candidates;
		}
		Path viaNotesDir = notes.getParent() == null ? null : notes.getParent().resolve(path);
		Path viaBase = lexicalBase.resolve(path);
		if (viaNotesDir != null) {
			candidates.add(viaNotesDir.toAbsolutePath().normalize());
		}
		candidates.add(viaBase.toAbsolutePath().normalize());
		return candidates;
	}

	/**
	 * Relativizes the candidate's canonical form against the canonical base,
	 * forward slashes.
	 */
	private static String relativizeForward(Path path, Path base) {
		return base.relativize(realOrNormalized(path)).toString().replace('\\', '/');
	}

	/**
	 * Whether a candidate's canonical form lies under the canonical base and a
	 * declared root.
	 */
	private static boolean isUnderRoots(Path candidate, ResolutionContext context) {
		Path canonicalCandidate = context.canonicalCandidate(candidate);
		if (!canonicalCandidate.startsWith(context.canonicalBase())) {
			return false;
		}
		for (Path canonicalRoot : context.canonicalRoots()) {
			if (canonicalCandidate.startsWith(canonicalRoot)) {
				return true;
			}
		}
		return false;
	}
}
