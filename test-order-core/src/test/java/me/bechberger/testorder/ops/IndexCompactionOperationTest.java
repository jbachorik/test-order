package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

class IndexCompactionOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void compactsDepsFilesIntoIndex() throws IOException {
		Path depsDir = tempDir.resolve(".test-order/deps");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\ncom.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");

		Path indexFile = tempDir.resolve(".test-order/test-order.idx");

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(depsDir, indexFile,
				PluginLog.NOOP);

		assertEquals(2, result.afterTestCount());
		assertTrue(Files.exists(indexFile));

		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
		assertEquals(Set.of("com.example.Bar"), map.get("com.example.BarTest"));
	}

	@Test
	void preservesExplicitNativeEdgesAcrossRepeatedCompactionAndRejectsUnmarkedOrAbsolutePaths() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.New\n");
		Path indexFile = tempDir.resolve("test-order.idx");

		DependencyMap oldMap = new DependencyMap();
		oldMap.put("com.example.FooTest",
				Set.of("com.example.Old", "src/native.cpp", "legacy/path.cpp", "/tmp/machine/native.cpp"));
		oldMap = oldMap.withNativeAugmentation(
				Map.of("com.example.FooTest", Set.of("src/native.cpp", "/tmp/machine/native.cpp")));
		oldMap.save(indexFile);

		List<String> warnings = new ArrayList<>();
		PluginLog log = new PluginLog() {
			@Override
			public void info(String message) {
			}

			@Override
			public void warn(String message) {
				warnings.add(message);
			}

			@Override
			public void debug(String message) {
			}
		};

		IndexCompactionOperation.compact(depsDir, indexFile, log);
		DependencyMap first = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.New", "src/native.cpp"), first.get("com.example.FooTest"));
		assertTrue(first.isNativeUnit("src/native.cpp"));
		assertFalse(first.get("com.example.FooTest").contains("legacy/path.cpp"));
		assertFalse(first.get("com.example.FooTest").contains("/tmp/machine/native.cpp"));
		assertTrue(warnings.stream().anyMatch(message -> message.contains("absolute native unit key")));
		assertTrue(warnings.stream().anyMatch(message -> message.contains("unmarked path-like")));

		IndexCompactionOperation.compact(depsDir, indexFile, log);
		DependencyMap second = DependencyMap.load(indexFile);
		assertTrue(second.get("com.example.FooTest").contains("src/native.cpp"));
		assertTrue(second.isNativeUnit("src/native.cpp"));
	}

	@Test
	void warnsAndDropsNativeEdgesForTestsAbsentFromRebuiltDeps() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		String currentTest = "com.example.CurrentTest";
		String staleTest = "com.example.StaleTest";
		Files.writeString(depsDir.resolve(currentTest + ".deps"), "com.example.Current\n");
		Path indexFile = tempDir.resolve("test-order.idx");

		DependencyMap oldMap = new DependencyMap();
		oldMap.put(currentTest, Set.of("com.example.Current"));
		oldMap.put(staleTest, Set.of("com.example.Stale"));
		oldMap = oldMap.withNativeAugmentation(
				Map.of(currentTest, Set.of("src/current.cpp"), staleTest, Set.of("src/stale.cpp")));
		oldMap.save(indexFile);

		List<String> warnings = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		PluginLog log = new PluginLog() {
			@Override
			public void info(String message) {
				infos.add(message);
			}

			@Override
			public void warn(String message) {
				warnings.add(message);
			}

			@Override
			public void debug(String message) {
			}
		};

		IndexCompactionOperation.compact(depsDir, indexFile, log);

		DependencyMap compacted = DependencyMap.load(indexFile);
		assertTrue(compacted.testClasses().contains(currentTest));
		assertFalse(compacted.testClasses().contains(staleTest));
		assertTrue(compacted.isNativeUnit("src/current.cpp"));
		assertTrue(compacted.get(currentTest).contains("src/current.cpp"));
		assertTrue(warnings.stream()
				.anyMatch(message -> message.contains(staleTest) && message.contains("absent from rebuilt .deps")));
		assertTrue(infos.stream().anyMatch(message -> message.contains("Preserved 1 native edge(s)")));
	}

	@Test
	void returnsNoDepsWhenDirectoryMissing() throws IOException {
		Path missingDepsDir = tempDir.resolve("does-not-exist");
		Path indexFile = tempDir.resolve(".test-order/test-order.idx");

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(missingDepsDir, indexFile,
				PluginLog.NOOP);

		assertEquals("No .deps directory", result.description());
		assertEquals(0, result.afterTestCount());
	}

	@Test
	void reportsRemovedStaleTestsAfterRebuild() throws IOException {
		Path depsDir = tempDir.resolve(".test-order/deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.KeptTest.deps"), "com.example.Kept\n");

		Path indexFile = tempDir.resolve(".test-order/test-order.idx");
		Files.createDirectories(indexFile.getParent());

		DependencyMap oldMap = new DependencyMap();
		oldMap.put("com.example.StaleTest", Set.of("com.example.Stale"));
		oldMap.put("com.example.KeptTest", Set.of("com.example.Kept"));
		oldMap.save(indexFile);

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(depsDir, indexFile,
				PluginLog.NOOP);

		assertEquals(2, result.beforeTestCount());
		assertEquals(1, result.afterTestCount());
		assertEquals(1, result.removedTests());
		assertTrue(result.hasChanges());
	}
}
