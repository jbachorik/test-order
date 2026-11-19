package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.nativecov.JniExportScanner;
import me.bechberger.testorder.nativecov.NativeCoverageParser;

/**
 * Collects native coverage data into the dependency index:
 *
 * <ol>
 * <li>Parses per-test coverage artifacts from the declared coverage directory
 * ({@link NativeCoverageParser}) into {@code test → native file} edges.</li>
 * <li>Scans the declared native source roots for JNI exports
 * ({@link JniExportScanner}) and intersects them with the index's MEMBER-mode
 * member dependencies: a test whose tracked members include a JNI-exported
 * {@code FQCN#method} gains an edge to the source file exporting it. This is
 * the Java↔native cross-check — it makes C++ changes select the Java tests
 * whose profiles exercise the changed native entry points even without gcov
 * data.</li>
 * <li>Merges all edges into the binary index under a file lock. Native units
 * are plain path-keyed deps (v2 index), so scoring and closure need no change.
 * </li>
 * </ol>
 *
 * <p>
 * Tests that crash lose their gcov data (the JVM dies before the process exits
 * and gcov flushes) — their artifacts are simply absent and the resulting edges
 * are missing; this is a documented limitation, not an error. sancov-based
 * collection is a follow-up.
 */
public final class NativeCollectOperation {

	private NativeCollectOperation() {
	}

	/** Statistics from one collect run. */
	public record Result(int artifactsParsed, int testsWithGcovEdges, int gcovEdgeCount, int jniExportCount,
			int crossCheckTests, int crossCheckEdgeCount, int totalTestsMerged, List<String> warnings) {

		public boolean hasEdges() {
			return gcovEdgeCount > 0 || crossCheckEdgeCount > 0;
		}
	}

	/**
	 * Runs the collect and merges the result into the index.
	 *
	 * @param projectRoot
	 *            project root (unit keys are relative to it)
	 * @param nativeRoots
	 *            declared native source roots
	 * @param coverageDir
	 *            directory with per-test coverage artifacts
	 * @param indexFile
	 *            binary dependency index; must already exist (learn must have run)
	 * @param log
	 *            logger
	 */
	public static Result collect(Path projectRoot, List<Path> nativeRoots, Path coverageDir, Path indexFile,
			PluginLog log) throws IOException {
		if (!Files.isRegularFile(indexFile)) {
			throw new IOException(
					"Dependency index not found at " + indexFile + ".\nRun: ./gradlew test -Dtestorder.mode=learn");
		}
		List<String> warnings = new ArrayList<>();

		// 1. gcov artifacts → test → native file edges
		NativeCoverageParser.Result parsed = NativeCoverageParser.parse(projectRoot, coverageDir, nativeRoots);
		warnings.addAll(parsed.warnings());

		// 2. JNI exports → member key → native file(s)
		Map<String, Set<String>> fileToExports = JniExportScanner.scan(projectRoot, nativeRoots);
		Map<String, Set<String>> exportToFiles = new HashMap<>();
		for (Map.Entry<String, Set<String>> e : fileToExports.entrySet()) {
			for (String memberKey : e.getValue()) {
				exportToFiles.computeIfAbsent(memberKey, k -> new TreeSet<>()).add(e.getKey());
			}
		}

		// 3. Cross-check: member deps ∩ JNI exports → test → native file edges
		Map<String, Set<String>> edges = new TreeMap<>();
		int crossCheckTests = 0;
		int crossCheckEdges = 0;
		int jniExportCount = exportToFiles.size();
		if (!exportToFiles.isEmpty()) {
			DependencyMap index = DependencyMap.load(indexFile);
			if (index.hasMemberDeps()) {
				Map<String, Set<String>> crossCheck = crossCheckMemberDeps(index, exportToFiles);
				for (Map.Entry<String, Set<String>> e : crossCheck.entrySet()) {
					edges.put(e.getKey(), e.getValue());
					crossCheckTests++;
					crossCheckEdges += e.getValue().size();
				}
			}
		}

		// 4. Merge gcov edges + cross-check edges into the index (file-locked)
		int gcovTests = 0;
		int gcovEdges = 0;
		for (Map.Entry<String, Set<String>> e : parsed.testToFiles().entrySet()) {
			Set<String> merged = edges.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
			merged.addAll(e.getValue());
			gcovTests++;
			gcovEdges += e.getValue().size();
		}

		int totalTests = 0;
		if (!edges.isEmpty()) {
			Map<String, Set<String>> unmodifiable = new TreeMap<>();
			edges.forEach((k, v) -> unmodifiable.put(k, Collections.unmodifiableSet(v)));
			DependencyMap.mergeFromAgent(indexFile, unmodifiable, Map.of(), Map.of(), Map.of());
			totalTests = unmodifiable.size();
		}

		log.info("[test-order] Native collect: parsed " + parsed.artifactsParsed() + " artifact(s), " + gcovTests
				+ " test(s) with " + gcovEdges + " gcov edge(s), " + jniExportCount
				+ " JNI export(s), cross-check added " + crossCheckEdges + " edge(s) for " + crossCheckTests
				+ " test(s)");
		if (!parsed.testToFiles().isEmpty() || !edges.isEmpty()) {
			log.info("[test-order] Native collect: merged native edges for " + totalTests + " test class(es) into "
					+ indexFile.getFileName());
		}
		for (String warning : warnings) {
			log.warn("[test-order] Native collect: " + warning);
		}
		return new Result(parsed.artifactsParsed(), gcovTests, gcovEdges, jniExportCount, crossCheckTests,
				crossCheckEdges, totalTests, warnings);
	}

	/**
	 * Computes the JNI cross-check edges, traversing method-member keys once for
	 * all test classes.
	 */
	static Map<String, Set<String>> crossCheckMemberDeps(DependencyMap index, Map<String, Set<String>> exportToFiles) {
		Map<String, Set<String>> classToMemberDeps = new HashMap<>();
		for (String methodKey : index.methodMemberKeys()) {
			classToMemberDeps.computeIfAbsent(methodClass(methodKey), k -> new TreeSet<>())
					.addAll(index.getMethodMemberDeps(methodKey));
		}

		Map<String, Set<String>> edges = new TreeMap<>();
		for (String test : index.testClasses()) {
			Set<String> memberKeys = new TreeSet<>(index.getMemberDeps(test));
			memberKeys.addAll(classToMemberDeps.getOrDefault(test, Set.of()));
			Set<String> files = null;
			for (String memberKey : memberKeys) {
				Set<String> exported = exportToFiles.get(memberKey);
				if (exported != null) {
					if (files == null) {
						files = new TreeSet<>();
					}
					files.addAll(exported);
				}
			}
			if (files != null) {
				edges.put(test, files);
			}
		}
		return edges;
	}

	/** Class part of a {@code className#methodName} method key. */
	private static String methodClass(String methodKey) {
		int hash = methodKey.indexOf('#');
		return hash > 0 ? methodKey.substring(0, hash) : methodKey;
	}
}
