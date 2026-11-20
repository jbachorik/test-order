package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

class NativeCollectOperationTest {

	private static final class CountingDependencyMap extends DependencyMap {

		private int methodMemberKeyIterators;

		@Override
		public Set<String> methodMemberKeys() {
			Set<String> keys = super.methodMemberKeys();
			return new AbstractSet<>() {
				@Override
				public Iterator<String> iterator() {
					methodMemberKeyIterators++;
					return keys.iterator();
				}

				@Override
				public int size() {
					return keys.size();
				}
			};
		}
	}

	@TempDir
	Path tempDir;

	private Path root;
	private Path nativeRoot;
	private Path coverageDir;
	private Path indexFile;

	private void setup() throws IOException {
		root = tempDir.resolve("root");
		nativeRoot = root.resolve("src/profiling");
		coverageDir = root.resolve("build/native-coverage");
		indexFile = root.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(nativeRoot);
		Files.createDirectories(coverageDir);
		Files.createDirectories(indexFile.getParent());
	}

	private void writeNativeFile(String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private void writeIndex() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#run"));
		map.save(indexFile);
	}

	@Test
	void declaredBinariesScopeCoverageAndJniEdgesToCompiledSources() throws IOException {
		setup();
		writeIndex();
		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");
		writeNativeFile("src/profiling/vendored.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Vendored_run(JNIEnv*, jobject) {}");
		Files.writeString(coverageDir.resolve("com.example.BarTest.json"),
				"{\"test\": \"com.example.BarTest\", \"coveredFiles\": [\"src/profiling/foo.cpp\", "
						+ "\"src/profiling/vendored.cpp\"]}",
				StandardCharsets.UTF_8);

		// Fake gcov notes: only foo.cpp was compiled into the tested build. The
		// extractor scans printable runs for tracked source extensions, so magic
		// and padding bytes around the embedded path are irrelevant.
		Path binaryDir = root.resolve("build/native-build/objects");
		Files.createDirectories(binaryDir);
		Files.writeString(binaryDir.resolve("foo.gcno"),
				"gcno\u00ff\u00ff\u0000" + root.toAbsolutePath().resolve("src/profiling/foo.cpp") + "\u0000",
				StandardCharsets.ISO_8859_1);

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP, java.util.List.of(binaryDir));

		assertEquals(1, result.jniExportCount(), "only the compiled source's export may be scanned");
		assertEquals(1, result.testsWithGcovEdges());
		assertEquals(1, result.crossCheckTests(), "cross-check must survive scoping for compiled sources");
		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("src/profiling/foo.cpp"), after.get("com.example.BarTest"),
				"coverage edges for sources never compiled into the tested build must be dropped");
	}

	@Test
	void declaredBinariesWithoutNotesFilesDisableScopingWithAWarning() throws IOException {
		setup();
		writeIndex();
		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");
		Files.writeString(coverageDir.resolve("com.example.BarTest.json"),
				"{\"test\": \"com.example.BarTest\", \"coveredFiles\": [\"src/profiling/foo.cpp\"]}",
				StandardCharsets.UTF_8);

		Path emptyBinaryDir = root.resolve("build/empty");
		Files.createDirectories(emptyBinaryDir);
		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP, java.util.List.of(emptyBinaryDir));

		// Degradation contract: no notes files → full unscoped pipeline.
		assertEquals(1, result.jniExportCount());
		assertEquals(1, result.testsWithGcovEdges());
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("scoping disabled")),
				"expected the scoping-disabled warning, got: " + result.warnings());
	}

	@Test
	void gcovEdgesAndJniCrossCheckMergeIntoIndex() throws IOException {
		setup();
		writeIndex();
		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");
		Files.writeString(coverageDir.resolve("com.example.BarTest.json"),
				"{\"test\": \"com.example.BarTest\", \"coveredFiles\": [\"src/profiling/foo.cpp\"]}",
				StandardCharsets.UTF_8);

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);

		// gcov edge for BarTest + cross-check edge for FooTest (its member dep
		// com.example.Foo#run is exported from foo.cpp)
		assertEquals(1, result.artifactsParsed());
		assertEquals(1, result.testsWithGcovEdges());
		assertEquals(1, result.jniExportCount());
		assertEquals(1, result.crossCheckTests());
		assertEquals(1, result.crossCheckEdgeCount());

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("src/profiling/foo.cpp"), after.get("com.example.BarTest"));
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}

	@Test
	void duplicateJniExportsInSeparateFilesAreCountedPerFileAndKeepBothEdges() throws IOException {
		setup();
		writeIndex();
		String export = "JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}";
		writeNativeFile("src/profiling/first.cpp", export);
		writeNativeFile("src/profiling/second.cpp", export);

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);

		assertEquals(2, result.jniExportCount());
		assertEquals(2, result.crossCheckEdgeCount());
		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/first.cpp", "src/profiling/second.cpp"),
				after.get("com.example.FooTest"));
	}

	@Test
	void crossCheckWorksWithoutCoverageArtifacts() throws IOException {
		setup();
		writeIndex();
		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);
		assertEquals(0, result.artifactsParsed());
		assertEquals(1, result.crossCheckEdgeCount());

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}

	@Test
	void crossCheckUnionsDependenciesByClassAndTraversesMethodKeysOnce() {
		String firstTest = "com.example.FirstTest";
		String secondTest = "com.example.SecondTest";
		CountingDependencyMap index = new CountingDependencyMap();
		index.put(firstTest, Set.of());
		index.put(secondTest, Set.of());
		index.putMemberDeps(firstTest, Set.of("com.example.First#classMember"));
		index.putMethodMemberDeps(firstTest + "#first", Set.of("com.example.First#methodOne"));
		index.putMethodMemberDeps(firstTest + "#second", Set.of("com.example.First#methodTwo"));
		index.putMemberDeps(secondTest, Set.of("com.example.Second#classMember"));
		index.putMethodMemberDeps(secondTest + "#first", Set.of("com.example.Second#methodOne"));

		Map<String, Set<String>> exportToFiles = Map.of("com.example.First#classMember",
				Set.of("natives/first-class.cpp"), "com.example.First#methodOne", Set.of("natives/first-one.cpp"),
				"com.example.First#methodTwo", Set.of("natives/first-two.cpp"), "com.example.Second#classMember",
				Set.of("natives/second-class.cpp"), "com.example.Second#methodOne", Set.of("natives/second-one.cpp"));

		Map<String, Set<String>> edges = NativeCollectOperation.crossCheckMemberDeps(index, exportToFiles);

		assertEquals(
				Map.of(firstTest, Set.of("natives/first-class.cpp", "natives/first-one.cpp", "natives/first-two.cpp"),
						secondTest, Set.of("natives/second-class.cpp", "natives/second-one.cpp")),
				edges);
		assertEquals(1, index.methodMemberKeyIterators,
				"method-member keys should be traversed once across all test classes");
	}

	@Test
	void missingIndexFailsWithRecoverableHint() throws IOException {
		setup();
		IOException e = assertThrows(IOException.class, () -> NativeCollectOperation.collect(root,
				java.util.List.of(nativeRoot), coverageDir, indexFile, PluginLog.NOOP));
		assertTrue(e.getMessage().contains("Run: ./gradlew test -Dtestorder.mode=learn"));
	}

	@Test
	void uncoveredFilesAreDroppedWithWarnings() throws IOException {
		setup();
		writeIndex();
		writeNativeFile("src/other/outside.cpp", "int x;");
		Files.writeString(coverageDir.resolve("com.example.FooTest.json"),
				"{\"test\": \"com.example.FooTest\", \"coveredFiles\": [\"src/other/outside.cpp\", \"src/profiling/foo.cpp\"]}",
				StandardCharsets.UTF_8);

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("outside the declared native source roots")));

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}

	@Test
	void wrapperNativeFallbackCrossChecksWhenExportNameDiffersFromMemberDep() throws IOException {
		setup();
		// MEMBER deps record the public wrapper tests call; the JNI export is the
		// *0 native impl — exact member-key intersection is empty by construction.
		DependencyMap map = new DependencyMap();
		map.put("com.example.WrapperTest", Set.of("com.example.Wrapper"));
		map.putMemberDeps("com.example.WrapperTest", Set.of("com.example.Wrapper#blockEnter"));
		// an unrelated test with no member deps on the wrapper class must gain nothing
		map.put("com.example.UnrelatedTest", Set.of("com.example.Other"));
		map.putMemberDeps("com.example.UnrelatedTest", Set.of("com.example.Other#whatever"));
		map.save(indexFile);

		writeNativeFile("src/profiling/wrapper.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Wrapper_blockEnter0(JNIEnv*, jobject) {}");

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);
		assertEquals(1, result.crossCheckTests());
		assertEquals(1, result.crossCheckEdgeCount());

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Wrapper", "src/profiling/wrapper.cpp"), after.get("com.example.WrapperTest"));
		assertEquals(Set.of("com.example.Other"), after.get("com.example.UnrelatedTest"));
	}

	@Test
	void wrapperNativeFallbackGroupsAllExportsOfOneClassPerTest() throws IOException {
		setup();
		DependencyMap map = new DependencyMap();
		map.put("com.example.WrapperTest", Set.of("com.example.Wrapper"));
		map.putMemberDeps("com.example.WrapperTest", Set.of("com.example.Wrapper#findFlag"));
		map.save(indexFile);

		writeNativeFile("src/profiling/a.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Wrapper_findFlag0(JNIEnv*, jobject) {}");
		writeNativeFile("src/profiling/b.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Wrapper_flags0(JNIEnv*, jobject) {}");

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);
		// class-level fallback unions every file exporting the wrapper class
		assertEquals(1, result.crossCheckTests());
		assertEquals(2, result.crossCheckEdgeCount());
		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Wrapper", "src/profiling/a.cpp", "src/profiling/b.cpp"),
				after.get("com.example.WrapperTest"));
	}

	@Test
	void exactMemberKeyMatchStillWinsOverClassFallback() throws IOException {
		setup();
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#run"));
		map.save(indexFile);

		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");
		writeNativeFile("src/profiling/other.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run0(JNIEnv*, jobject) {}");

		NativeCollectOperation.collect(root, java.util.List.of(nativeRoot), coverageDir, indexFile, PluginLog.NOOP);
		DependencyMap after = DependencyMap.load(indexFile);
		// exact match on run; class fallback adds other.cpp too (same class) — both
		// edges land, the exact match is not displaced
		assertEquals(Set.of("com.example.Foo", "src/profiling/foo.cpp", "src/profiling/other.cpp"),
				after.get("com.example.FooTest"));
	}

	@Test
	void methodMemberDepsAreCrossCheckedToo() throws IOException {
		setup();
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		map.putMethodMemberDeps("com.example.FooTest#testRun", Set.of("com.example.Foo#run"));
		map.save(indexFile);

		writeNativeFile("src/profiling/foo.cpp",
				"JNIEXPORT void JNICALL Java_com_example_Foo_run(JNIEnv*, jobject) {}");

		NativeCollectOperation.Result result = NativeCollectOperation.collect(root, java.util.List.of(nativeRoot),
				coverageDir, indexFile, PluginLog.NOOP);
		assertEquals(1, result.crossCheckTests());

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}
}
