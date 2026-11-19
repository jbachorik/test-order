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
		assertEquals(2, result.artifactsParsed());
		assertEquals(Set.of("src/profiling/foo.cpp"), result.testToFiles().get("com.example.A"));
		assertEquals(Set.of("src/profiling/foo.cpp", "src/profiling/bar.cpp"),
				result.testToFiles().get("com.example.B"));
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
				    {"file": "/abs/path/elsewhere/bar.cpp", "lines": []}
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
