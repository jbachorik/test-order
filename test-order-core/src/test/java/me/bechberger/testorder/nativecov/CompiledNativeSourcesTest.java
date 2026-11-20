package me.bechberger.testorder.nativecov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.NativeChangeDetector;

/**
 * Tolerance is the point of the extractor: gcov notes format versions vary
 * across GCC and clang releases, so it scans printable runs for tracked source
 * extensions instead of struct-parsing. These tests pin the tolerant behavior
 * plus the degradation contract (empty result → scoping disabled).
 */
class CompiledNativeSourcesTest {

	@TempDir
	Path tempDir;

	private static String gcno(String... embeddedPaths) {
		StringBuilder sb = new StringBuilder("gcno\u00ff\u00ff");
		for (String p : embeddedPaths) {
			sb.append('\u0000').append(p).append('\u0000');
		}
		return sb.toString();
	}

	@Test
	void extractsAbsoluteAndRelativeSourcePathsFromNotesFiles() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/objects");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		// absolute compile-time path under the key base
		Files.writeString(binaryDir.resolve("foo.gcno"),
				gcno(base.toAbsolutePath().resolve("src/profiling/foo.cpp").toString()), StandardCharsets.ISO_8859_1);
		// relative path recorded relative to the build invocation directory
		Files.writeString(binaryDir.resolve("bar.gcno"), gcno("src/profiling/bar.cpp"), StandardCharsets.ISO_8859_1);
		// object-name form: foo.cpp.o yields the source key foo.cpp
		Files.writeString(binaryDir.resolve("baz.gcno"),
				gcno(base.toAbsolutePath().resolve("src/profiling/baz.cpp.o").toString()), StandardCharsets.ISO_8859_1);

		Set<String> keys = CompiledNativeSources.compiledSourceKeys(List.of(binaryDir), base, List.of(roots));

		assertEquals(Set.of("src/profiling/foo.cpp", "src/profiling/bar.cpp", "src/profiling/baz.cpp"), keys);
	}

	@Test
	void emittedKeysMatchNativeChangeDetectorAndRepeatedCandidatesAreDeduplicated() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/objects");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		Path source = base.toAbsolutePath().resolve("src/profiling/repeated.cpp");
		Files.writeString(binaryDir.resolve("repeated.gcno"), gcno(source.toString(), source.toString()),
				StandardCharsets.ISO_8859_1);

		Set<String> keys = CompiledNativeSources.compiledSourceKeys(List.of(binaryDir), base, List.of(roots));

		assertEquals(Set.of(NativeChangeDetector.nativeKey(base, source)), keys);
	}

	@Test
	void symlinkedKeyBaseStillEmitsLexicalUnitKeys() throws IOException {
		Path base = tempDir.resolve("project-real");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/objects");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		Path alias = tempDir.resolve("project-alias");
		try {
			Files.createSymbolicLink(alias, base);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			assumeTrue(false, "symbolic links are not supported in this environment: " + e.getMessage());
		}
		Path source = alias.resolve("src/profiling/alias.cpp");
		Files.writeString(binaryDir.resolve("alias.gcno"), gcno(source.toString()), StandardCharsets.ISO_8859_1);

		Set<String> keys = CompiledNativeSources.compiledSourceKeys(List.of(binaryDir), alias,
				List.of(alias.resolve("src/profiling")));

		assertEquals(Set.of(NativeChangeDetector.nativeKey(alias, source)), keys);
	}

	@Test
	void dropsPathsOutsideTheKeyBaseAndOutsideTheDeclaredRoots() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/objects");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		// compiled from a sibling project — outside the key base entirely
		Files.writeString(binaryDir.resolve("other.gcno"),
				gcno(tempDir.toAbsolutePath().resolve("elsewhere/src/profiling/x.cpp").toString()),
				StandardCharsets.ISO_8859_1);
		// under the key base but not under any declared root
		Files.writeString(binaryDir.resolve("untracked.gcno"),
				gcno(base.toAbsolutePath().resolve("vendored/include/y.h").toString()), StandardCharsets.ISO_8859_1);

		Set<String> keys = CompiledNativeSources.compiledSourceKeys(List.of(binaryDir), base, List.of(roots));

		assertEquals(Set.of(), keys);
	}

	@Test
	void singleFileBinaryFormUsesTheSameStemNotesSibling() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/lib");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		Files.writeString(binaryDir.resolve("libx.so"), "ELF binary bytes", StandardCharsets.ISO_8859_1);
		Files.writeString(binaryDir.resolve("libx.gcno"),
				gcno(base.toAbsolutePath().resolve("src/profiling/foo.cpp").toString()), StandardCharsets.ISO_8859_1);

		Set<String> keys = CompiledNativeSources.compiledSourceKeys(List.of(binaryDir.resolve("libx.so")), base,
				List.of(roots));

		assertEquals(Set.of("src/profiling/foo.cpp"), keys);
	}

	@Test
	void noNotesFilesYieldAnEmptyScopingSet() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Path binaryDir = base.resolve("build/objects");
		Files.createDirectories(roots);
		Files.createDirectories(binaryDir);
		Files.writeString(binaryDir.resolve("not-a-notes-file.bin"), "random build output",
				StandardCharsets.ISO_8859_1);

		assertEquals(Set.of(), CompiledNativeSources.compiledSourceKeys(List.of(binaryDir), base, List.of(roots)));
	}

	@Test
	void emptyBinariesYieldAnEmptyScopingSet() throws IOException {
		Path base = tempDir.resolve("project");
		Path roots = base.resolve("src/profiling");
		Files.createDirectories(roots);

		assertEquals(Set.of(), CompiledNativeSources.compiledSourceKeys(List.of(), base, List.of(roots)));
		assertTrue(CompiledNativeSources.compiledSourceKeys(null, base, List.of(roots)).isEmpty());
	}
}
