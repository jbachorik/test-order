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
