package me.bechberger.testorder.nativecov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.NativeChangeDetector;

class NativeCoverageParserTest {

	@TempDir
	Path tempDir;

	private Path root;
	private Path coverageDir;
	private List<Path> nativeRoots;

	private void setup() throws Exception {
		root = tempDir.resolve("root");
		coverageDir = root.resolve("build/native-coverage");
		Files.createDirectories(coverageDir);
		Files.createDirectories(root.resolve("src/profiling"));
		nativeRoots = List.of(root.resolve("src/profiling"));
	}

	private Path writeArtifact(String name, String content) throws Exception {
		Path file = coverageDir.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void parsesGenericPerTestJson() throws Exception {
		setup();
		writeArtifact("com.example.FooTest.json", """
				{"test": "com.example.FooTest", "coveredFiles": ["src/profiling/foo.cpp", "src/profiling/bar.hpp"]}
				""");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(1, result.artifactsParsed());
		assertEquals(Set.of("src/profiling/foo.cpp", "src/profiling/bar.hpp"),
				result.testToFiles().get("com.example.FooTest"));
	}

	@Test
	void coveredPathUsesTheSharedNativeUnitKey() throws Exception {
		setup();
		writeArtifact("com.example.KeyTest.json",
				"{\"test\":\"com.example.KeyTest\",\"coveredFiles\":[\"src/profiling/foo.cpp\"]}");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		String expected = NativeChangeDetector.nativeKey(root, root.resolve("src/profiling/foo.cpp"));

		assertEquals(Set.of(expected), result.testToFiles().get("com.example.KeyTest"));
	}

	@Test
	void missingOrNullCoveredFilesAreEmptyCoverageLists() throws Exception {
		setup();
		writeArtifact("com.example.MissingTest.json", "{\"test\": \"com.example.MissingTest\"}");
		writeArtifact("com.example.NullTest.json", "{\"test\": \"com.example.NullTest\", \"coveredFiles\": null}");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(2, result.artifactsParsed());
		assertTrue(result.warnings().isEmpty());
		assertTrue(result.testToFiles().values().stream().allMatch(Set::isEmpty));
	}

	@Test
	void testFieldWinsOverFilename() throws Exception {
		setup();
		writeArtifact("not-a-fqcn.json",
				"{\"test\": \"com.example.Actual\", \"coveredFiles\": [\"src/profiling/foo.cpp\"]}");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertTrue(result.testToFiles().containsKey("com.example.Actual"));
	}

	@Test
	void filenameStemIsUsedWhenTestFieldIsAbsent() throws Exception {
		setup();
		writeArtifact("com.example.StemTest.json", "{\"coveredFiles\": [\"src/profiling/foo.cpp\"]}");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.StemTest"));
	}

	@Test
	void nonFqcnFilenameWithoutTestFieldIsSkippedWithWarning() throws Exception {
		setup();
		writeArtifact("run-42.json", "{\"coveredFiles\": [\"src/profiling/foo.cpp\"]}");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertTrue(result.testToFiles().isEmpty());
		assertEquals(1, result.warnings().size());
	}

	@Test
	void parsesJsonl() throws Exception {
		setup();
		writeArtifact("coverage.jsonl", """
				{"test": "com.example.A", "coveredFiles": ["src/profiling/foo.cpp"]}
				{"test": "com.example.B", "coveredFiles": ["src/profiling/foo.cpp", "src/profiling/bar.cpp"]}
				""");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(1, result.artifactsParsed());
		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.A"));
		assertEquals(Set.of("src/profiling/foo.cpp", "src/profiling/bar.cpp"),
				result.testToFiles().get("com.example.B"));
	}

	@Test
	void jsonlRecordsWithoutCoveredFilesAreAcceptedAsEmptyCoverage() throws Exception {
		setup();
		writeArtifact("coverage.jsonl", """
				{"test":"com.example.MissingFilesTest"}
				{"test":"com.example.NullFilesTest","coveredFiles":null}
				""");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(1, result.artifactsParsed());
		assertTrue(result.warnings().isEmpty());
		assertTrue(result.testToFiles().values().stream().allMatch(Set::isEmpty));
	}

	@Test
	void malformedJsonlLineIsSkippedWithWarning() throws Exception {
		setup();
		writeArtifact("coverage.jsonl", """
				{"test": "com.example.A", "coveredFiles": ["src/profiling/foo.cpp"]}
				{not json}
				""");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(1, result.artifactsParsed());
		assertEquals(1, result.warnings().size());
		assertTrue(result.testToFiles().containsKey("com.example.A"));
	}

	@Test
	void parsesGcovrJson() throws Exception {
		setup();
		writeArtifact("com.example.GcovrTest.json", """
				{
				  "format_version": "0.5",
				  "files": [
				    {"file": "src/profiling/foo.cpp", "lines": [{"line_number": 1, "count": 3}]},
				    {"file": "/abs/path/elsewhere/bar.cpp", "lines": [{"line_number": 2, "count": 1}]}
				  ]
				}
				""");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(1, result.artifactsParsed());
		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.GcovrTest"));
		// outside the declared roots → dropped with a warning
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("outside the declared native source roots")));
	}

	@Test
	void zeroCoverageGcovrEntriesDoNotCreateEdgesButArtifactCounts() throws Exception {
		setup();
		writeArtifact("com.example.ZeroTest.json", """
				{
				  "format_version": "0.5",
				  "files": [
				    {"file": "src/profiling/zero.cpp", "line_rate": 0, "lines": [{"line_number": 1, "count": 4}]},
				    {"file": "src/profiling/uncovered.cpp", "lines": [{"line_number": 2, "count": 0}]}
				  ]
				}
				""");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(1, result.artifactsParsed());
		assertTrue(result.testToFiles().values().stream().allMatch(Set::isEmpty));
	}

	@Test
	void invalidArtifactPathIsDroppedAndLaterEntriesAreParsed() throws Exception {
		setup();
		String nulEscape = "\\" + "u0000";
		writeArtifact("com.example.InvalidPathTest.json",
				"{\"test\":\"com.example.InvalidPathTest\",\"coveredFiles\":[\"src/profiling/bad" + nulEscape
						+ ".cpp\",\"src/profiling/good.cpp\"]}");

		writeArtifact("coverage.jsonl",
				"{\"test\":\"com.example.JsonlInvalidPathTest\",\"coveredFiles\":[\"src/profiling/bad" + nulEscape
						+ ".cpp\",\"src/profiling/later.cpp\"]}");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(2, result.artifactsParsed());
		assertEquals(Set.of("src/profiling/good.cpp"), result.testToFiles().get("com.example.InvalidPathTest"));
		assertEquals(Set.of("src/profiling/later.cpp"), result.testToFiles().get("com.example.JsonlInvalidPathTest"));
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("InvalidPathTest.json")));
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("coverage.jsonl")));
	}

	@Test
	void oversizedJsonAndJsonlAreSkippedWhole() throws Exception {
		setup();
		String property = "testorder.native.maxArtifactBytes";
		String previous = System.getProperty(property);
		try {
			System.setProperty(property, "80");
			writeArtifact("com.example.BigTest.json",
					"{\"test\":\"com.example.BigTest\",\"padding\":\"" + "x".repeat(120) + "\"}");
			writeArtifact("coverage.jsonl",
					"{\"test\":\"com.example.LineTest\",\"padding\":\"" + "y".repeat(120) + "\"}\\n");
			writeArtifact("com.example.SmallTest.json", "{\"test\":\"com.example.SmallTest\"}");

			NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

			assertEquals(1, result.artifactsParsed());
			assertEquals(2, result.warnings().size());
			assertTrue(result.warnings().stream().anyMatch(w -> w.contains("BigTest.json")));
			assertTrue(result.warnings().stream().anyMatch(w -> w.contains("coverage.jsonl")));
		} finally {
			if (previous == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, previous);
			}
		}
	}

	@Test
	void projectRootNativeRootAcceptsRelativeKeysAndRejectsAbsoluteKeys() throws Exception {
		setup();
		nativeRoots = List.of(root);
		writeArtifact("com.example.RootTest.json", "{\"test\":\"com.example.RootTest\",\"coveredFiles\":["
				+ "\"src/profiling/foo.cpp\",\"/outside/native.cpp\",\"C:/outside/native.cpp\"]}");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.RootTest"));
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("outside the declared native source roots")));
	}

	@Test
	void rootContainmentUsesPathBoundaries() throws Exception {
		setup();
		Files.createDirectories(root.resolve("src/profiling-old"));
		writeArtifact("com.example.BoundaryTest.json", "{\"test\":\"com.example.BoundaryTest\",\"coveredFiles\":["
				+ "\"src/profiling/foo.cpp\",\"src/profiling-old/bar.cpp\"]}");

		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);

		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.BoundaryTest"));
	}

	@Test
	void dropsNonNativeFilesWithWarning() throws Exception {
		setup();
		writeArtifact("com.example.FooTest.json",
				"{\"test\": \"com.example.FooTest\", \"coveredFiles\": [\"src/profiling/notes.txt\", \"src/profiling/foo.cpp\"]}");
		NativeCoverageParser.Result result = NativeCoverageParser.parse(root, coverageDir, nativeRoots);
		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.FooTest"));
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not a native source file")));
	}

	@Test
	void emptyOrMissingCoverageDirYieldsEmptyResult() throws Exception {
		setup();
		assertEquals(0, NativeCoverageParser.parse(root, coverageDir, nativeRoots).artifactsParsed());
		assertTrue(NativeCoverageParser.parse(root, root.resolve("nope"), nativeRoots).testToFiles().isEmpty());
	}
}
