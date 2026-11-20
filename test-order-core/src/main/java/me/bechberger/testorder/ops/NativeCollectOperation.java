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
import me.bechberger.testorder.changes.NativeChangeDetector;
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
 * {@code FQCN#method} gains an edge to the source file exporting it. When no
 * exact member matches (the common wrapper pattern: tests call
 * {@code Wrapper#method} while the native impl exports
 * {@code Wrapper#method0}), the fallback matches on the export's <b>class</b>
 * part — any member-level dependency on a class with JNI exports gains an edge
 * to the files exporting that class. This is the Java↔native cross-check — it
 * makes C++ changes select the Java tests whose profiles exercise the changed
 * native entry points even without gcov data.</li>
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
		return collect(projectRoot, nativeRoots, coverageDir, indexFile, log, List.of());
	}

	/**
	 * Collects gcov and JNI cross-check edges, scoped to the declared binaries.
	 *
	 * <p>
	 * When {@code binaries} is non-empty (the {@code nativeTests.binaries} DSL),
	 * the gcov notes files under the declared build outputs identify the sources
	 * that were actually compiled into the tested build, and both the JNI export
	 * scan and the coverage parsing are restricted to them — sources under the
	 * roots that were never compiled (vendored code, stale artifacts) can no longer
	 * contribute false-positive edges. Degradation contract: when no notes files
	 * are found, scoping is disabled with a warning and the full unscoped pipeline
	 * runs; scoping is subtractive only.
	 */
	public static Result collect(Path projectRoot, List<Path> nativeRoots, Path coverageDir, Path indexFile,
			PluginLog log, List<Path> binaries) throws IOException {
		if (!Files.isRegularFile(indexFile)) {
			throw new IOException(
					"Dependency index not found at " + indexFile + ".\nRun: ./gradlew test -Dtestorder.mode=learn");
		}
		List<String> warnings = new ArrayList<>();

		// 0. Compiled-source scoping from the declared binaries' gcov notes. An
		// empty set disables scoping (subtractive-only contract).
		Set<String> compiledKeys = me.bechberger.testorder.nativecov.CompiledNativeSources.compiledSourceKeys(binaries,
				projectRoot, nativeRoots);
		boolean scoped = !binaries.isEmpty();
		if (scoped && compiledKeys.isEmpty()) {
			warnings.add("no gcov notes files (.gcno) found under the declared binaries — compiled-source "
					+ "scoping disabled, scanning all declared source roots");
		} else if (scoped) {
			log.info("[test-order] Native collect: compiled-source scoping active — " + compiledKeys.size()
					+ " compiled source file(s) from the declared binaries");
		}

		// 1. gcov artifacts → test → native file edges
		NativeCoverageParser.Result parsed = NativeCoverageParser.parse(projectRoot, coverageDir, nativeRoots);
		warnings.addAll(parsed.warnings());
		Map<String, Set<String>> testToFiles = parsed.testToFiles();
		if (scoped && !compiledKeys.isEmpty()) {
			Map<String, Set<String>> filtered = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, Set<String>> e : testToFiles.entrySet()) {
				Set<String> kept = new java.util.TreeSet<>(e.getValue());
				kept.retainAll(compiledKeys);
				if (!kept.isEmpty()) {
					filtered.put(e.getKey(), kept);
				}
			}
			testToFiles = filtered;
		}

		// 2. JNI exports → member key → native file(s)
		Map<String, Set<String>> fileToExports = JniExportScanner.scan(projectRoot, nativeRoots);
		if (scoped && !compiledKeys.isEmpty()) {
			fileToExports.keySet().retainAll(compiledKeys);
		}
		Map<String, Set<String>> exportToFiles = new HashMap<>();
		for (Map.Entry<String, Set<String>> e : fileToExports.entrySet()) {
			for (String memberKey : e.getValue()) {
				exportToFiles.computeIfAbsent(memberKey, k -> new TreeSet<>()).add(e.getKey());
			}
		}

		// 3. Gcov edges are filtered before the native-aware locked merge so absolute
		// machine-specific paths are never persisted.
		Map<String, Set<String>> gcovEdgesByTest = filterAbsoluteNativeKeys(testToFiles, warnings);
		int gcovTests = gcovEdgesByTest.size();
		int gcovEdges = gcovEdgesByTest.values().stream().mapToInt(Set::size).sum();
		int jniExportCount = fileToExports.values().stream().mapToInt(Set::size).sum();

		// 4. Derive JNI/member edges from the same index instance that is merged,
		// under one file lock and one deserialization.
		Map<String, Set<String>> crossCheck = DependencyMap.mergeFromAgentWithDerivedDeps(indexFile, gcovEdgesByTest,
				index -> {
					if (!index.hasMemberDeps() || exportToFiles.isEmpty()) {
						return Map.of();
					}
					return filterAbsoluteNativeKeys(crossCheckMemberDeps(index, exportToFiles), warnings);
				});
		int crossCheckTests = crossCheck.size();
		int crossCheckEdges = crossCheck.values().stream().mapToInt(Set::size).sum();
		Set<String> mergedTests = new java.util.HashSet<>(gcovEdgesByTest.keySet());
		mergedTests.addAll(crossCheck.keySet());
		int totalTests = mergedTests.size();
		log.info("[test-order] Native collect: parsed " + parsed.artifactsParsed() + " artifact(s), " + gcovTests
				+ " test(s) with " + gcovEdges + " gcov edge(s), " + jniExportCount
				+ " JNI export(s), cross-check added " + crossCheckEdges + " edge(s) for " + crossCheckTests
				+ " test(s)");
		if (!gcovEdgesByTest.isEmpty() || !crossCheck.isEmpty()) {
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
		// Class-level fallback: MEMBER-mode deps record the public wrappers tests call
		// (e.g. JavaProfiler#blockEnter) while JNI exports are the *0 native impls
		// (JavaProfiler#blockEnter0) — the exact member-key intersection is empty by
		// construction on wrapper-style JNI codebases. Fall back to the export's CLASS
		// part: any member-level dependency on a class with JNI exports gains an edge
		// to the files exporting that class. This may over-select (a test touching an
		// unrelated member of the wrapper class) but never under-select.
		Map<String, Set<String>> exportsByClass = new HashMap<>();
		for (Map.Entry<String, Set<String>> e : exportToFiles.entrySet()) {
			exportsByClass.computeIfAbsent(methodClass(e.getKey()), k -> new TreeSet<>()).addAll(e.getValue());
		}

		Map<String, Set<String>> edges = new TreeMap<>();
		for (String test : index.testClasses()) {
			Set<String> memberKeys = new TreeSet<>(index.getMemberDeps(test));
			memberKeys.addAll(classToMemberDeps.getOrDefault(test, Set.of()));
			Set<String> classes = new TreeSet<>();
			Set<String> files = null;
			for (String memberKey : memberKeys) {
				classes.add(methodClass(memberKey));
				Set<String> exported = exportToFiles.get(memberKey);
				if (exported != null) {
					if (files == null) {
						files = new TreeSet<>();
					}
					files.addAll(exported);
				}
			}
			// Class-level fallback unions every file exporting the class — an exact
			// member key alone would leave sibling native impl files of the same
			// class unselected.
			for (String clazz : classes) {
				Set<String> exported = exportsByClass.get(clazz);
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

	private static Map<String, Set<String>> filterAbsoluteNativeKeys(Map<String, Set<String>> source,
			List<String> warnings) {
		Map<String, Set<String>> filtered = new TreeMap<>();
		for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
			Set<String> paths = new TreeSet<>();
			for (String path : entry.getValue()) {
				if (NativeChangeDetector.isAbsoluteNativeKey(path)) {
					warnings.add("Ignoring absolute native unit key '" + path + "'");
				} else {
					paths.add(path);
				}
			}
			if (!paths.isEmpty()) {
				filtered.put(entry.getKey(), Collections.unmodifiableSet(paths));
			}
		}
		return filtered;
	}

	/** Class part of a {@code className#methodName} method key. */
	private static String methodClass(String methodKey) {
		int hash = methodKey.indexOf('#');
		return hash > 0 ? methodKey.substring(0, hash) : methodKey;
	}
}
