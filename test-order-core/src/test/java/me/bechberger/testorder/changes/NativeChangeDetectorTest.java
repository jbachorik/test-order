package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeChangeDetectorTest {

	@TempDir
	Path tempDir;

	private Path write(Path relative, String content) throws IOException {
		Path file = tempDir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	// ── extension / key helpers ───────────────────────────────────────

	@Test
	void recognizesNativeExtensions() {
		assertTrue(NativeChangeDetector.isNativeSourceFile("src/foo.cpp"));
		assertTrue(NativeChangeDetector.isNativeSourceFile("src/foo.HPP"));
		assertTrue(NativeChangeDetector.isNativeSourceFile("src/foo.c"));
		assertTrue(NativeChangeDetector.isNativeSourceFile("src/foo.h"));
		assertFalse(NativeChangeDetector.isNativeSourceFile("src/Foo.java"));
		assertFalse(NativeChangeDetector.isNativeSourceFile("src/foo.txt"));
		assertFalse(NativeChangeDetector.isNativeSourceFile(null));
		// "fooc" is not a native file just because it ends in 'c'
		assertFalse(NativeChangeDetector.isNativeSourceFile("src/fooc"));
	}

	@Test
	void nativeKeyIsProjectRootRelativeWithForwardSlashes(@TempDir Path root) throws IOException {
		Path file = root.resolve("src/profiling/foo.cpp");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "int main() {}", StandardCharsets.UTF_8);
		assertEquals("src/profiling/foo.cpp", NativeChangeDetector.nativeKey(root, file));
		// files outside the project root fall back to the absolute path
		Path outside = Files.createTempDirectory("outside").resolve("bar.cpp");
		Files.writeString(outside, "int x;", StandardCharsets.UTF_8);
		assertEquals(outside.toString(), NativeChangeDetector.nativeKey(root, outside));
	}

	// ── since-last-run ────────────────────────────────────────────────

	@Test
	void sinceLastRunDetectsChangedAndDeletedFiles() throws IOException {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		write(root.resolve("n/src/a.cpp"), "int a;");
		write(root.resolve("n/src/b.hpp"), "#pragma once");
		write(root.resolve("n/src/Ignore.java"), "class Ignore {}");
		List<Path> roots = List.of(root.resolve("n/src"));
		Path hashFile = tempDir.resolve("native-hashes.lz4");

		// first run: no snapshot → everything counts as changed, snapshot is written
		Set<String> first = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null,
				false);
		assertEquals(Set.of("n/src/a.cpp", "n/src/b.hpp"), first);
		assertTrue(Files.exists(hashFile));

		// second run: no changes
		Set<String> unchanged = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile,
				null, false);
		assertTrue(unchanged.isEmpty());

		// modify one, delete one, add one
		Files.writeString(root.resolve("n/src/a.cpp"), "int a2;", StandardCharsets.UTF_8);
		Files.delete(root.resolve("n/src/b.hpp"));
		write(root.resolve("n/src/c.cc"), "int c;");
		Set<String> second = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile,
				null, false);
		assertEquals(Set.of("n/src/a.cpp", "n/src/b.hpp", "n/src/c.cc"), second);
	}

	@Test
	void readOnlyDetectionDoesNotAdvanceTheSnapshot() throws IOException {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		write(root.resolve("n/a.cpp"), "int a;");
		List<Path> roots = List.of(root.resolve("n"));
		Path hashFile = tempDir.resolve("native-hashes.lz4");
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null, false);

		Files.writeString(root.resolve("n/a.cpp"), "int a2;", StandardCharsets.UTF_8);
		NativeChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null);
		// read-only → snapshot unchanged → still detected on the next real run
		Set<String> again = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null,
				false);
		assertEquals(Set.of("n/a.cpp"), again);
	}

	@Test
	void explicitPathsTakePrecedenceOverDetection() throws IOException {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		write(root.resolve("n/a.cpp"), "int a;");
		Set<String> explicit = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), tempDir.resolve("h.lz4"), "src/x.cpp, src/y.hpp", false);
		assertEquals(Set.of("src/x.cpp", "src/y.hpp"), explicit);
	}

	// ── git modes ─────────────────────────────────────────────────────

	private void git(Path dir, String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(dir.toFile());
		pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		pb.environment().put("GIT_CONFIG_COUNT", "3");
		pb.environment().put("GIT_CONFIG_KEY_0", "gc.auto");
		pb.environment().put("GIT_CONFIG_VALUE_0", "0");
		pb.environment().put("GIT_CONFIG_KEY_1", "user.email");
		pb.environment().put("GIT_CONFIG_VALUE_1", "t@example.com");
		pb.environment().put("GIT_CONFIG_KEY_2", "user.name");
		pb.environment().put("GIT_CONFIG_VALUE_2", "t");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		if (p.waitFor() != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed");
		}
	}

	@Test
	void uncommittedGitModeDetectsChangedNativeFiles() throws IOException, InterruptedException {
		Path root = tempDir.resolve("repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		write(root.resolve("n/a.cpp"), "int a;");
		write(root.resolve("n/readme.txt"), "text");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "init");

		Files.writeString(root.resolve("n/a.cpp"), "int a2;", StandardCharsets.UTF_8);
		write(root.resolve("n/b.cpp"), "int b;");
		write(root.resolve("n/c.java"), "class C {}");

		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), tempDir.resolve("h.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp", "n/b.cpp"), changed);
	}

	@Test
	void sinceLastCommitGitModeDetectsCommittedNativeChanges() throws IOException, InterruptedException {
		Path root = tempDir.resolve("repo2");
		Files.createDirectories(root);
		git(root, "init", "-q");
		write(root.resolve("n/a.cpp"), "int a;");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "init");

		Files.writeString(root.resolve("n/a.cpp"), "int a2;", StandardCharsets.UTF_8);
		write(root.resolve("n/new.hpp"), "#pragma once");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "change");

		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, root,
				List.of(root.resolve("n")), tempDir.resolve("h.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp", "n/new.hpp"), changed);
	}

	// ── snapshot helper ───────────────────────────────────────────────

	@Test
	void snapshotWritesAUsableBaseline() throws IOException {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		write(root.resolve("n/a.cpp"), "int a;");
		Path hashFile = tempDir.resolve("native-hashes.lz4");
		NativeChangeDetector.snapshot(root, List.of(root.resolve("n")), hashFile);

		// baseline written → no changes detected
		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root,
				List.of(root.resolve("n")), hashFile, null, false);
		assertTrue(changed.isEmpty());
		Map<String, String> hashes = NativeChangeDetector.scanNativeRoots(root, List.of(root.resolve("n")));
		assertEquals(Set.of("n/a.cpp"), hashes.keySet());
	}
}
