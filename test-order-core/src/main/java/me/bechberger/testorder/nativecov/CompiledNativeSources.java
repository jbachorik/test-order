package me.bechberger.testorder.nativecov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
		// Canonicalize through symlinks: on macOS the temp root is /var (JUnit,
		// and often the recorded compile path) while Gradle hands out the
		// canonical /private/var — without toRealPath the two never match and
		// scoping silently disables.
		Path base = realOrNormalized(keyBase);
		for (Path binary : binaries) {
			for (Path notes : notesFilesFor(binary)) {
				keys.addAll(sourceKeysInNotes(notes, base, roots));
			}
		}
		return keys;
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
	private static Set<String> sourceKeysInNotes(Path notes, Path base, List<Path> roots) throws IOException {
		Set<String> keys = new TreeSet<>();
		if (Files.size(notes) > MAX_GCNO_BYTES) {
			return keys;
		}
		byte[] bytes = Files.readAllBytes(notes);
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		Matcher matcher = SOURCE_PATH.matcher(text);
		while (matcher.find()) {
			for (String candidate : resolveCandidate(matcher.group().trim(), notes, base)) {
				if (isUnderRoots(candidate, base, roots)) {
					keys.add(candidate);
				}
			}
		}
		return keys;
	}

	/**
	 * Candidate unit-key forms for one extracted path: absolute relativized, or
	 * both relative bases.
	 */
	private static List<String> resolveCandidate(String raw, Path notes, Path base) {
		List<String> forms = new ArrayList<>();
		String normalized = raw.replace('\\', '/');
		Path p = Path.of(normalized);
		Path absolute = p.isAbsolute() ? p : null;
		if (absolute == null) {
			Path viaNotesDir = notes.getParent() == null ? null : notes.getParent().resolve(p);
			Path viaBase = base.resolve(p);
			if (viaNotesDir != null && startsWith(viaNotesDir, base)) {
				forms.add(relativizeForward(viaNotesDir, base));
			}
			forms.add(relativizeForward(viaBase, base));
			return forms;
		}
		if (startsWith(absolute, base)) {
			forms.add(relativizeForward(absolute, base));
		}
		return forms;
	}

	private static boolean startsWith(Path path, Path base) {
		return realOrNormalized(path).startsWith(base);
	}

	private static String relativizeForward(Path path, Path base) {
		return base.relativize(realOrNormalized(path)).toString().replace('\\', '/');
	}

	/** Whether a project-relative unit key lies under one of the declared roots. */
	private static boolean isUnderRoots(String key, Path base, List<Path> roots) {
		Path absolute = base.resolve(key);
		for (Path root : roots) {
			if (realOrNormalized(absolute).startsWith(realOrNormalized(root))) {
				return true;
			}
		}
		return false;
	}
}
