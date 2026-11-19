package me.bechberger.testorder.nativecov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.changes.NativeChangeDetector;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;

/**
 * Parses per-test native coverage artifacts from a consumer-declared directory
 * into {@code test class FQCN → covered native source files} edges.
 *
 * <p>
 * Three artifact shapes are accepted (see README "Native coverage artifacts"):
 * <ol>
 * <li><b>Generic per-test JSON</b> — files named {@code *.json} containing
 * {@code {"test": "<FQCN>", "coveredFiles": ["<path>", ...]}}. The {@code test}
 * field wins; when absent, the filename stem is used as the test FQCN.</li>
 * <li><b>Generic JSONL</b> — {@code *.jsonl} files, one {@code {"test": ...,
 * "coveredFiles": ...}} object per line; the {@code test} field is
 * required.</li>
 * <li><b>gcovr JSON</b> — {@code *.json} files containing a top-level
 * {@code "files": [{"file": "<path>", ...}, ...]} array (gcovr {@code --json}
 * output); covered files are extracted from the entries and the test name comes
 * from the filename stem.</li>
 * </ol>
 *
 * <p>
 * Covered paths may be absolute or relative to the project root; they are
 * normalized to the shared native unit key (projectRoot-relative, forward
 * slashes, see {@link NativeChangeDetector}). Paths that are not native source
 * files or not under a declared native source root are dropped with a warning.
 */
public final class NativeCoverageParser {

	private NativeCoverageParser() {
	}

	/**
	 * Parsed artifacts: per-test covered native file keys plus warnings for dropped
	 * input.
	 */
	public record Result(Map<String, Set<String>> testToFiles, int artifactsParsed, List<String> warnings) {

		public static Result empty() {
			return new Result(Map.of(), 0, List.of());
		}
	}

	/**
	 * Parses the coverage directory.
	 *
	 * @param projectRoot
	 *            project root for path normalization and unit keys
	 * @param coverageDir
	 *            directory containing the per-test artifacts
	 * @param nativeRoots
	 *            declared native source roots; covered files outside these roots
	 *            are dropped
	 */
	public static Result parse(Path projectRoot, Path coverageDir, List<Path> nativeRoots) {
		if (coverageDir == null || !Files.isDirectory(coverageDir)) {
			return Result.empty();
		}
		// Precompute root prefixes for containment checks (projectRoot-relative keys)
		Set<String> rootPrefixes = new TreeSet<>();
		for (Path root : nativeRoots) {
			if (root == null) {
				continue;
			}
			Path abs = root.toAbsolutePath().normalize();
			String key = NativeChangeDetector.nativeKey(projectRoot, abs);
			// Directory key — normalize to a prefix with trailing slash semantics
			rootPrefixes.add(key.endsWith("/") ? key : key + "/");
		}

		Map<String, Set<String>> testToFiles = new TreeMap<>();
		List<String> warnings = new ArrayList<>();
		int[] parsed = new int[1];

		List<Path> artifacts;
		try (Stream<Path> walk = Files.walk(coverageDir)) {
			artifacts = walk.filter(Files::isRegularFile).filter(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return n.endsWith(".json") || n.endsWith(".jsonl");
			}).sorted().toList();
		} catch (IOException e) {
			warnings.add("Failed to list coverage directory " + coverageDir + ": " + e.getMessage());
			return new Result(Map.of(), 0, warnings);
		}

		for (Path artifact : artifacts) {
			try {
				String name = artifact.getFileName().toString().toLowerCase(Locale.ROOT);
				if (name.endsWith(".jsonl")) {
					parseJsonl(artifact, projectRoot, rootPrefixes, testToFiles, warnings, parsed);
				} else {
					parseJson(artifact, projectRoot, rootPrefixes, testToFiles, warnings, parsed);
				}
			} catch (IOException | me.bechberger.util.json.JSONParseException e) {
				warnings.add("Failed to read coverage artifact " + artifact + ": " + e.getMessage());
			}
		}
		return new Result(testToFiles, parsed[0], warnings);
	}

	private static void parseJson(Path artifact, Path projectRoot, Set<String> rootPrefixes,
			Map<String, Set<String>> testToFiles, List<String> warnings, int[] parsed) throws IOException {
		Object root;
		try {
			root = JSONParser.parse(Files.readString(artifact, StandardCharsets.UTF_8));
		} catch (me.bechberger.util.json.JSONParseException e) {
			warnings.add("Coverage artifact " + artifact.getFileName() + " is not valid JSON — skipped");
			return;
		}
		Map<String, Object> map = Util.asMap(root);
		if (map == null) {
			warnings.add("Coverage artifact " + artifact.getFileName() + " is not a JSON object — skipped");
			return;
		}
		String test = strOrNull(map.get("test"));
		if (map.containsKey("files")) {
			// gcovr JSON: {"files": [{"file": "...", ...}, ...]}
			if (test == null) {
				test = testFromFilename(artifact);
			}
			if (test == null) {
				warnings.add("gcovr artifact " + artifact.getFileName()
						+ " has no 'test' field and its filename is not a valid test FQCN — skipped");
				return;
			}
			int before = testToFiles.getOrDefault(test, Set.of()).size();
			for (Object entry : Util.asList(map.get("files"))) {
				Map<String, Object> entryMap = Util.asMap(entry);
				if (entryMap == null) {
					continue;
				}
				addCoveredFile(test, strOrNull(entryMap.get("file")), projectRoot, rootPrefixes, testToFiles, warnings,
						artifact);
			}
			parsed[0]++;
			logArtifact(test, testToFiles.getOrDefault(test, Set.of()).size() - before, artifact);
			return;
		}
		if (test == null) {
			test = testFromFilename(artifact);
		}
		if (test == null) {
			warnings.add("Coverage artifact " + artifact.getFileName()
					+ " has no 'test' field and its filename is not a valid test FQCN — skipped");
			return;
		}
		int before = testToFiles.getOrDefault(test, Set.of()).size();
		for (Object file : Util.asList(map.get("coveredFiles"))) {
			addCoveredFile(test, file instanceof String s ? s : null, projectRoot, rootPrefixes, testToFiles, warnings,
					artifact);
		}
		parsed[0]++;
		logArtifact(test, testToFiles.getOrDefault(test, Set.of()).size() - before, artifact);
	}

	private static void parseJsonl(Path artifact, Path projectRoot, Set<String> rootPrefixes,
			Map<String, Set<String>> testToFiles, List<String> warnings, int[] parsed) throws IOException {
		List<String> lines = Files.readAllLines(artifact, StandardCharsets.UTF_8);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty()) {
				continue;
			}
			Map<String, Object> map;
			try {
				map = Util.asMap(JSONParser.parse(line));
			} catch (IOException | me.bechberger.util.json.JSONParseException e) {
				warnings.add(artifact.getFileName() + " line " + (i + 1) + ": invalid JSON — skipped");
				continue;
			}
			if (map == null) {
				warnings.add(artifact.getFileName() + " line " + (i + 1) + ": not a JSON object — skipped");
				continue;
			}
			String test = strOrNull(map.get("test"));
			if (test == null) {
				warnings.add(artifact.getFileName() + " line " + (i + 1) + ": missing 'test' field — skipped");
				continue;
			}
			int before = testToFiles.getOrDefault(test, Set.of()).size();
			for (Object file : Util.asList(map.get("coveredFiles"))) {
				addCoveredFile(test, file instanceof String s ? s : null, projectRoot, rootPrefixes, testToFiles,
						warnings, artifact);
			}
			parsed[0]++;
		}
	}

	private static void addCoveredFile(String test, String covered, Path projectRoot, Set<String> rootPrefixes,
			Map<String, Set<String>> testToFiles, List<String> warnings, Path artifact) {
		if (covered == null || covered.isBlank()) {
			return;
		}
		Path resolved = Path.of(covered);
		if (!resolved.isAbsolute()) {
			resolved = projectRoot.resolve(resolved);
		}
		resolved = resolved.toAbsolutePath().normalize();
		if (!NativeChangeDetector.isNativeSourceFile(resolved.toString())) {
			warnings.add("Covered file " + covered + " (test " + test + ") is not a native source file — dropped");
			return;
		}
		String key = NativeChangeDetector.nativeKey(projectRoot, resolved);
		boolean underRoot = rootPrefixes.stream().anyMatch(key::startsWith);
		if (!underRoot) {
			warnings.add("Covered file " + key + " (test " + test
					+ ") is outside the declared native source roots — dropped");
			return;
		}
		testToFiles.computeIfAbsent(test, k -> new TreeSet<>()).add(key);
	}

	private static void logArtifact(String test, int files, Path artifact) {
		if (files == 0) {
			TestOrderLogger.debug("Coverage artifact {} contributed no native files for {}", artifact, test);
		}
	}

	/** Test FQCN from the artifact filename stem, or null when it cannot be one. */
	private static String testFromFilename(Path artifact) {
		String name = artifact.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		if (stem.isEmpty() || !stem.contains(".")) {
			return null;
		}
		for (String part : stem.split("\\.", -1)) {
			if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
				return null;
			}
			for (int i = 1; i < part.length(); i++) {
				if (!Character.isJavaIdentifierPart(part.charAt(i))) {
					return null;
				}
			}
		}
		return stem;
	}

	private static String strOrNull(Object o) {
		return o instanceof String s && !s.isBlank() ? s : null;
	}
}
