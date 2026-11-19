package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
	void mergeFromAgentAddsNativeEdgesWithoutLosingJavaEdges() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Prod"));
		map.save(indexFile);

		DependencyMap.mergeFromAgent(indexFile, Map.of("com.example.FooTest", Set.of("src/profiling/foo.cpp")),
				Map.of(), Map.of(), Map.of());

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Prod", "src/profiling/foo.cpp"), loaded.get("com.example.FooTest"));
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
		current.save(indexFile);

		// rebuild from a .deps file that only has the Java edge
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.New\n", StandardCharsets.UTF_8);
		me.bechberger.testorder.ops.IndexCompactionOperation.compact(depsDir, indexFile,
				me.bechberger.testorder.ops.PluginLog.NOOP);

		DependencyMap after = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.New", "src/profiling/foo.cpp"), after.get("com.example.FooTest"));
	}
}
