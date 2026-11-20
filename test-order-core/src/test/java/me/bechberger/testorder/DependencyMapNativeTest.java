package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.ops.TestClassDiscovery;

/**
 * Round-trips for format version 2 (native path-keyed dependency units) and the
 * v1 read / v2 write compatibility contract.
 */
class DependencyMapNativeTest {

	@TempDir
	Path tempDir;

	@Test
	void formatVersionIs2() {
		assertEquals(2, DependencyMap.FORMAT_VERSION);
		assertEquals(1, DependencyMap.MIN_SUPPORTED_VERSION);
	}

	@Test
	void roundTripsNativePathKeyedDeps() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod", "src/profiling/foo.cpp"));
		map.put("com.example.BarTest", Set.of("src/profiling/foo.cpp", "src/profiling/bar.cpp"));
		map.save(indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(2, loaded.size());
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), loaded.get("com.example.FooTest"));
		assertEquals(Set.of("src/profiling/foo.cpp", "src/profiling/bar.cpp"), loaded.get("com.example.BarTest"));
	}

	@Test
	void nativeUnitMarkersRoundTripAndGenericAugmentationOnlyCarriesThem() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		DependencyMap nativeMap = map
				.withNativeAugmentation(Map.of("com.example.FooTest", Set.of("src/profiling/foo.cpp")));
		DependencyMap genericAugmentation = nativeMap
				.withAugmentation(Map.of("com.example.FooTest", Set.of("com.example.Extra")));

		assertTrue(genericAugmentation.isNativeUnit("src/profiling/foo.cpp"));
		assertFalse(genericAugmentation.isNativeUnit("com.example.Extra"));
		DependencyMap mergedCopy = new DependencyMap();
		mergedCopy.mergeWith(genericAugmentation);
		assertTrue(mergedCopy.isNativeUnit("src/profiling/foo.cpp"));

		Path indexFile = tempDir.resolve("native-markers.lz4");
		genericAugmentation.save(indexFile);
		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("src/profiling/foo.cpp"), loaded.nativeUnits());
	}

	@Test
	void nativeDerivedMergeUsesLoadedIndexEvenWithNoBaseEdgesAndSkipsEmptySave() throws IOException {
		Path indexFile = tempDir.resolve("derived-merge.lz4");
		DependencyMap initial = new DependencyMap();
		initial.put("com.example.FooTest", Set.of("com.example.Prod"));
		initial.save(indexFile);
		AtomicBoolean callbackCalled = new AtomicBoolean();

		Map<String, Set<String>> derived = DependencyMap.mergeFromAgentWithDerivedDeps(indexFile, Map.of(), loaded -> {
			callbackCalled.set(true);
			assertEquals(Set.of("com.example.Prod"), loaded.get("com.example.FooTest"));
			return Map.of("com.example.FooTest", Set.of("src/native.cpp"));
		});
		assertTrue(callbackCalled.get());
		assertEquals(Map.of("com.example.FooTest", Set.of("src/native.cpp")), derived);
		DependencyMap merged = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/native.cpp"), merged.get("com.example.FooTest"));
		assertTrue(merged.isNativeUnit("src/native.cpp"));

		byte[] beforeEmptyMerge = Files.readAllBytes(indexFile);
		AtomicBoolean emptyCallbackCalled = new AtomicBoolean();
		DependencyMap.mergeFromAgentWithDerivedDeps(indexFile, Map.of(), loaded -> {
			emptyCallbackCalled.set(true);
			return Map.of();
		});
		assertTrue(emptyCallbackCalled.get());
		assertTrue(java.util.Arrays.equals(beforeEmptyMerge, Files.readAllBytes(indexFile)));
	}

	@Test
	void nativeDerivedMergeMarksBothBaseAndDerivedEdges() throws IOException {
		Path indexFile = tempDir.resolve("derived-and-base-merge.lz4");
		DependencyMap initial = new DependencyMap();
		initial.put("com.example.FooTest", Set.of("com.example.Prod"));
		initial.save(indexFile);

		DependencyMap.mergeFromAgentWithDerivedDeps(indexFile, Map.of("com.example.FooTest", Set.of("src/gcov.cpp")),
				loaded -> Map.of("com.example.FooTest", Set.of("src/jni.cpp")));

		DependencyMap merged = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/gcov.cpp", "src/jni.cpp"), merged.get("com.example.FooTest"));
		assertTrue(merged.isNativeUnit("src/gcov.cpp"));
		assertTrue(merged.isNativeUnit("src/jni.cpp"));
	}

	@Test
	void filteredMapsCarryNativeMarkers() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		map.putModule("com.example.FooTest", "module-a");
		DependencyMap nativeMap = map.withNativeAugmentation(Map.of("com.example.FooTest", Set.of("src/native.cpp")));
		assertTrue(nativeMap.filterForModule("module-a", null).isNativeUnit("src/native.cpp"));
		assertTrue(TestClassDiscovery.filterToModuleId(nativeMap, "module-a").isNativeUnit("src/native.cpp"));

		Path testClassesDir = tempDir.resolve("classes");
		Path classFile = testClassesDir.resolve("com/example/FooTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[0]);
		assertTrue(TestClassDiscovery.filterToModule(nativeMap, testClassesDir).isNativeUnit("src/native.cpp"));

		Path sourceRoot = tempDir.resolve("src/test/java");
		Path source = sourceRoot.resolve("com/example/FooTest.java");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "package com.example; class FooTest {}\n");
		DependencyMap discovered = TestClassDiscovery.filterToModuleBySourceRoot(nativeMap, sourceRoot);
		assertTrue(discovered.isNativeUnit("src/native.cpp"));
	}

	@Test
	void mergeFromAgentAddsNativeEdgesWithoutLosingJavaEdges() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod", "src/profiling/old.cpp"));
		map = map.withNativeAugmentation(Map.of("com.example.FooTest", Set.of("src/profiling/old.cpp")));
		map.save(indexFile);

		DependencyMap.mergeFromAgent(indexFile, Map.of("com.example.FooTest", Set.of("src/profiling/foo.cpp")),
				Map.of(), Map.of(), Map.of());

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/old.cpp", "src/profiling/foo.cpp"),
				loaded.get("com.example.FooTest"));
		assertTrue(loaded.isNativeUnit("src/profiling/old.cpp"));
		assertFalse(loaded.isNativeUnit("src/profiling/foo.cpp"));
	}

	/**
	 * v1 read / v2 write: a saved v2 file with the version field patched back to 1
	 * must load (v1 never contains path-keyed deps, so this simulates a legacy
	 * index) and a re-save must write v2 again.
	 */
	@Test
	void readsV1FilesAndUpgradesOnSave() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		map.save(indexFile);

		patchVersion(indexFile, (short) 1);
		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.FooTest"), loaded.testClasses());
		assertEquals(Set.of("com.example.Prod"), loaded.get("com.example.FooTest"));

		// re-save upgrades to v2; the patched-v1 file loads cleanly afterwards
		loaded.save(indexFile);
		patchVersion(indexFile, (short) 3);
		try {
			DependencyMap.load(indexFile);
			throw new AssertionError("version 3 must be rejected");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Unsupported dependency index format version"));
		}
	}

	/** Rewrites the 2-byte big-endian format version inside the LZ4 payload. */
	private static void patchVersion(Path indexFile, short newVersion) throws IOException {
		// Decompress, patch, recompress via DependencyMap's own LZ4 helpers
		byte[] decompressed;
		try (var in = new net.jpountz.lz4.LZ4FrameInputStream(Files.newInputStream(indexFile))) {
			decompressed = in.readAllBytes();
		}
		// header: magic(4) + version(2)
		decompressed[4] = (byte) ((newVersion >> 8) & 0xFF);
		decompressed[5] = (byte) (newVersion & 0xFF);
		try (var out = new net.jpountz.lz4.LZ4FrameOutputStream(Files.newOutputStream(indexFile))) {
			out.write(decompressed);
		}
	}

	@Test
	void compactionPreservesNativeEdges() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Files.createDirectories(depsDir);

		DependencyMap current = new DependencyMap();
		current.put("com.example.FooTest", Set.of("com.example.Prod", "src/profiling/foo.cpp"));
		current = current.withNativeAugmentation(Map.of("com.example.FooTest", Set.of("src/profiling/foo.cpp")));
		current.save(indexFile);

		// rebuild from a .deps file that only has the Java edge
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.New\n", StandardCharsets.UTF_8);
		me.bechberger.testorder.ops.IndexCompactionOperation.compact(depsDir, indexFile,
				me.bechberger.testorder.ops.PluginLog.NOOP);

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.New", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}
}
