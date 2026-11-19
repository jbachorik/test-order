package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.PersistenceSupport;

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
	void nativeKeyIsProjectRootRelativeWithForwardSlashes(@TempDir Path root, @TempDir Path outsideRoot)
			throws IOException {
		Path file = root.resolve("src/profiling/foo.cpp");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "int main() {}", StandardCharsets.UTF_8);
		assertEquals("src/profiling/foo.cpp", NativeChangeDetector.nativeKey(root, file));
		// files outside the project root fall back to the absolute path
		Path outside = outsideRoot.resolve("bar.cpp");
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
	void symlinkedRootGitModeKeysMatchSinceLastRunKeys() throws Exception {
		Path root = tempDir.resolve("repo");
		Path shared = root.resolve("shared");
		Files.createDirectories(shared);
		git(root, "init", "-q");
		// declared root is a symlink INSIDE the project pointing at a real dir outside
		// it
		write(root.resolve("shared/a.cpp"), "int a;");
		Files.createSymbolicLink(root.resolve("n"), root.relativize(shared));
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "init");

		Files.writeString(root.resolve("shared/a.cpp"), "int a2;", StandardCharsets.UTF_8);
		write(root.resolve("shared/b.cpp"), "int b;");
		List<Path> roots = List.of(root.resolve("n"));

		// git mode must key from the DECLARED root form, matching SINCE_LAST_RUN
		Set<String> gitKeys = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root, roots,
				tempDir.resolve("h.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp", "n/b.cpp"), gitKeys);

		Path hashFile = tempDir.resolve("native-symlink-hashes.lz4");
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null, false);
		Files.writeString(root.resolve("shared/a.cpp"), "int a3;", StandardCharsets.UTF_8);
		Set<String> sinceLastRun = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots,
				hashFile, null, false);
		assertEquals(Set.of("n/a.cpp"), sinceLastRun);
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
	void gitModesMapNestedProjectChangesToProjectRelativeKeys() throws IOException, InterruptedException {
		Path gitRoot = tempDir.resolve("nested-repo");
		Path projectRoot = gitRoot.resolve("project");
		Path nativeRoot = projectRoot.resolve("n");
		Files.createDirectories(nativeRoot);
		git(gitRoot, "init", "-q");
		Path source = write(nativeRoot.resolve("a.cpp"), "int a;");
		git(gitRoot, "add", ".");
		git(gitRoot, "commit", "-q", "-m", "initial");

		Files.writeString(source, "int a2;", StandardCharsets.UTF_8);
		Set<String> uncommitted = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, projectRoot,
				List.of(nativeRoot), tempDir.resolve("nested-hashes.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp"), uncommitted);

		git(gitRoot, "add", ".");
		git(gitRoot, "commit", "-q", "-m", "update");
		Set<String> sinceLastCommit = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot,
				List.of(nativeRoot), tempDir.resolve("nested-hashes.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp"), sinceLastCommit);
		assertEquals(NativeChangeDetector.nativeKey(projectRoot, source), sinceLastCommit.iterator().next());
	}

	@Test
	void quotedNonAsciiGitPathsAreDecodedBeforeFiltering() throws IOException, InterruptedException {
		Path root = tempDir.resolve("quoted-repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		Path source = write(root.resolve("n/ünïcode.cpp"), "int value;");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");
		git(root, "config", "core.quotepath", "true");
		Files.writeString(source, "int changed;", StandardCharsets.UTF_8);

		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), tempDir.resolve("quoted-hashes.lz4"), null, false);
		assertEquals(Set.of("n/ünïcode.cpp"), changed);
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
	void singleCommitSinceLastCommitFallsBackToTrackedNativeFiles() throws IOException, InterruptedException {
		Path root = tempDir.resolve("single-commit-repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		write(root.resolve("n/a.cpp"), "int a;");
		write(root.resolve("n/Ignore.java"), "class Ignore {}");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");

		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, root,
				List.of(root.resolve("n")), tempDir.resolve("single-commit-hashes.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp"), changed);
	}

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
		assertFalse(Files.exists(PersistenceSupport.temporarySibling(hashFile)));
	}

	@Test
	void failedSnapshotSerializationPropagatesAndRemovesTemporaryBaseline() throws IOException {
		Path root = tempDir.resolve("failed-snapshot-root");
		Path nativeRoot = root.resolve("n");
		Path source = write(nativeRoot.resolve("a.cpp"), "int a;");
		Path hashFile = tempDir.resolve("failed-snapshot/hashes.lz4");
		NativeChangeDetector.HashOutputStreamFactory failingFactory = path -> {
			OutputStream delegate = Files.newOutputStream(path);
			return new OutputStream() {
				private int remaining = 16;

				@Override
				public void write(int value) throws IOException {
					if (remaining == 0) {
						throw new IOException("injected snapshot write failure");
					}
					delegate.write(value);
					remaining--;
				}

				@Override
				public void write(byte[] bytes, int offset, int length) throws IOException {
					int accepted = Math.min(length, remaining);
					if (accepted > 0) {
						delegate.write(bytes, offset, accepted);
						remaining -= accepted;
					}
					if (accepted < length) {
						throw new IOException("injected snapshot write failure");
					}
				}

				@Override
				public void close() throws IOException {
					delegate.close();
				}
			};
		};

		assertThrows(IOException.class, () -> NativeChangeDetector
				.saveHashes(Map.of("n/a.cpp", "0123456789abcdef".repeat(12)), hashFile, failingFactory));
		assertFalse(Files.exists(hashFile));
		assertFalse(Files.exists(PersistenceSupport.temporarySibling(hashFile)));
		assertFalse(Files.exists(PersistenceSupport.resolveLoadPath(hashFile)));

		Set<String> detected = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root,
				List.of(nativeRoot), hashFile, null, false);
		assertEquals(Set.of("n/a.cpp"), detected);
		assertTrue(Files.exists(source));
	}
}
