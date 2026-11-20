package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
		assertEquals(outside.toAbsolutePath().normalize().toString().replace('\\', '/'),
				NativeChangeDetector.nativeKey(root, outside));
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
	void sinceLastRunDetectsNativeCommentOnlyChanges() throws IOException {
		Path root = tempDir.resolve("comment-root");
		Path source = root.resolve("n/a.cpp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "int value; // a", StandardCharsets.UTF_8);
		Path hashFile = tempDir.resolve("comment-hashes.lz4");
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, List.of(root.resolve("n")), hashFile,
				null, false);

		Files.writeString(source, "int value; // b", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root,
				List.of(root.resolve("n")), hashFile, null, false);
		assertEquals(Set.of("n/a.cpp"), changed);
	}

	@Test
	void matchingMetadataReusesNativeSnapshotHash() throws IOException {
		Path root = tempDir.resolve("metadata-root");
		Path source = root.resolve("n/a.cpp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "int a;", StandardCharsets.UTF_8);
		Path hashFile = tempDir.resolve("metadata-hashes.lz4");
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, List.of(root.resolve("n")), hashFile,
				null, false);
		FileTime originalTime = Files.getLastModifiedTime(source);
		Files.writeString(source, "int b;", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(source, originalTime);

		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root,
				List.of(root.resolve("n")), hashFile, null, false);
		assertTrue(changed.isEmpty(), "an exact size+mtime match intentionally reuses the cached hash");
	}

	@Test
	void legacyNativeSnapshotIsReadOnlyUntilWritableMigration() throws IOException {
		Path root = tempDir.resolve("legacy-root");
		Path source = root.resolve("n/a.cpp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "int a;", StandardCharsets.UTF_8);
		Path hashFile = tempDir.resolve("legacy-hashes.lz4");
		new FileHashStore(Map.of("n/a.cpp", FileHashStore.sha256RawSafe(source))).save(hashFile);
		byte[] legacyBytes = Files.readAllBytes(hashFile);

		assertTrue(NativeChangeDetector
				.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, List.of(root.resolve("n")), hashFile, null, true)
				.isEmpty());
		assertArrayEquals(legacyBytes, Files.readAllBytes(hashFile),
				"read-only scanning must not migrate the snapshot");
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, List.of(root.resolve("n")), hashFile,
				null, false);

		try (var lz4 = new net.jpountz.lz4.LZ4FrameInputStream(Files.newInputStream(hashFile));
				var reader = new java.io.BufferedReader(new java.io.InputStreamReader(lz4, StandardCharsets.UTF_8))) {
			assertEquals("#native-hash-snapshot-v2", reader.readLine());
			String[] row = reader.readLine().split("\\t", -1);
			assertEquals("n/a.cpp", row[0]);
			assertEquals(5, row.length);
			assertEquals(Long.toString(Files.size(source)), row[2]);
		}
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
		NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, root, roots, hashFile, null, true);
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
		pb.environment().put("GIT_CONFIG_COUNT", "5");
		pb.environment().put("GIT_CONFIG_KEY_0", "gc.auto");
		pb.environment().put("GIT_CONFIG_VALUE_0", "0");
		pb.environment().put("GIT_CONFIG_KEY_1", "user.email");
		pb.environment().put("GIT_CONFIG_VALUE_1", "t@example.com");
		pb.environment().put("GIT_CONFIG_KEY_2", "user.name");
		pb.environment().put("GIT_CONFIG_VALUE_2", "t");
		pb.environment().put("GIT_CONFIG_KEY_3", "commit.gpgsign");
		pb.environment().put("GIT_CONFIG_VALUE_3", "false");
		pb.environment().put("GIT_CONFIG_KEY_4", "core.fsmonitor");
		pb.environment().put("GIT_CONFIG_VALUE_4", "false");
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process p = pb.start();
		try {
			if (!p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				throw new IOException("git command timed out: " + String.join(" ", args));
			}
			if (p.exitValue() != 0) {
				throw new RuntimeException("git " + String.join(" ", args) + " failed");
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
			throw e;
		} finally {
			if (p.isAlive()) {
				p.destroyForcibly();
			}
			try {
				p.getInputStream().close();
			} catch (IOException ignored) {
			}
			try {
				p.getOutputStream().close();
			} catch (IOException ignored) {
			}
		}
	}

	@Test
	void gitSupportCleansUpTimedOutInterruptedAndExceptionalProcesses() throws Exception {
		StubProcess timedOut = new StubProcess(new byte[0], 0, true, false, false);
		assertThrows(IOException.class, () -> GitSupport.runGitBytes(tempDir, true, builder -> {
			assertTrue(builder.redirectErrorStream());
			return timedOut;
		}, "status"));
		assertTrue(timedOut.forciblyDestroyed);

		StubProcess interrupted = new StubProcess(new byte[0], 0, false, true, false);
		try {
			assertThrows(IOException.class,
					() -> GitSupport.runGitBytes(tempDir, true, builder -> interrupted, "status"));
			assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
			assertTrue(interrupted.forciblyDestroyed);
		} finally {
			Thread.interrupted();
		}

		StubProcess failedDuringWait = new StubProcess(new byte[0], 0, false, false, true);
		assertThrows(IllegalStateException.class,
				() -> GitSupport.runGitBytes(tempDir, true, builder -> failedDuringWait, "status"));
		assertTrue(failedDuringWait.forciblyDestroyed, "exceptions after process start must not leak a live child");
	}

	@Test
	void gitSupportLeavesNormallyExitedProcessesAlone() throws Exception {
		StubProcess success = new StubProcess("output".getBytes(StandardCharsets.UTF_8), 0, false, false, false);
		assertArrayEquals("output".getBytes(StandardCharsets.UTF_8),
				GitSupport.runGitBytes(tempDir, true, builder -> success, "status"));
		assertFalse(success.forciblyDestroyed);

		StubProcess failure = new StubProcess("failure".getBytes(StandardCharsets.UTF_8), 2, false, false, false);
		assertThrows(IOException.class, () -> GitSupport.runGitBytes(tempDir, true, builder -> failure, "status"));
		assertFalse(failure.forciblyDestroyed, "a normally exited nonzero process must not be killed");
	}

	private static final class StubProcess extends Process {

		private final byte[] output;
		private final int exitCode;
		private final boolean timeOut;
		private final boolean interruptWait;
		private final boolean failWait;
		private boolean alive = true;
		private boolean forciblyDestroyed;

		StubProcess(byte[] output, int exitCode, boolean timeOut, boolean interruptWait, boolean failWait) {
			this.output = output;
			this.exitCode = exitCode;
			this.timeOut = timeOut;
			this.interruptWait = interruptWait;
			this.failWait = failWait;
		}

		@Override
		public OutputStream getOutputStream() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return new ByteArrayInputStream(output);
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() {
			return exitCode;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
			if (interruptWait) {
				throw new InterruptedException("test interruption");
			}
			if (failWait) {
				throw new IllegalStateException("test wait failure");
			}
			if (timeOut) {
				return false;
			}
			alive = false;
			return true;
		}

		@Override
		public int exitValue() {
			if (alive) {
				throw new IllegalThreadStateException("process is still running");
			}
			return exitCode;
		}

		@Override
		public void destroy() {
			alive = false;
		}

		@Override
		public Process destroyForcibly() {
			forciblyDestroyed = true;
			alive = false;
			return this;
		}

		@Override
		public boolean isAlive() {
			return alive;
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
		RepoChangeListings.Snapshot uncommittedSnapshot = RepoChangeListings
				.acquire(RepoChangeListings.resolveGitRoot(gitRoot));
		assertEquals(uncommitted, NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, projectRoot,
				List.of(nativeRoot), tempDir.resolve("nested-hashes.lz4"), null, true, uncommittedSnapshot));

		git(gitRoot, "add", ".");
		git(gitRoot, "commit", "-q", "-m", "update");
		Set<String> sinceLastCommit = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot,
				List.of(nativeRoot), tempDir.resolve("nested-hashes.lz4"), null, false);
		assertEquals(Set.of("n/a.cpp"), sinceLastCommit);
		assertEquals(NativeChangeDetector.nativeKey(projectRoot, source), sinceLastCommit.iterator().next());
		RepoChangeListings.Snapshot committedSnapshot = RepoChangeListings
				.acquire(RepoChangeListings.resolveGitRoot(gitRoot));
		assertEquals(sinceLastCommit, NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot,
				List.of(nativeRoot), tempDir.resolve("nested-hashes.lz4"), null, true, committedSnapshot));
	}

	@Test
	void nestedGitRootAndOverlappingNativeRootsUseProjectRelativeKeysForBothGitModes() throws Exception {
		Path gitRoot = tempDir.resolve("nested-multi-root-repo");
		Path projectRoot = gitRoot.resolve("module");
		Path nativeRoot = projectRoot.resolve("n");
		Path nestedNativeRoot = nativeRoot.resolve("sub");
		Path secondNativeRoot = projectRoot.resolve("other");
		Files.createDirectories(nestedNativeRoot);
		Files.createDirectories(secondNativeRoot);
		git(gitRoot, "init", "-q");
		Path first = projectRoot.resolve("n/a.cpp");
		Path nested = projectRoot.resolve("n/sub/b.cpp");
		Path second = projectRoot.resolve("other/c.cpp");
		Files.writeString(first, "int a;", StandardCharsets.UTF_8);
		Files.writeString(nested, "int b;", StandardCharsets.UTF_8);
		Files.writeString(second, "int c;", StandardCharsets.UTF_8);
		git(gitRoot, "add", ".");
		git(gitRoot, "commit", "-q", "-m", "initial");

		List<Path> roots = List.of(nativeRoot, nestedNativeRoot, secondNativeRoot);
		Set<String> expected = Set.of("n/a.cpp", "n/sub/b.cpp", "other/c.cpp");
		Files.writeString(first, "int a2;", StandardCharsets.UTF_8);
		Files.writeString(nested, "int b2;", StandardCharsets.UTF_8);
		Files.writeString(second, "int c2;", StandardCharsets.UTF_8);
		Set<String> uncommitted = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, projectRoot, roots,
				tempDir.resolve("nested-multi-root-hashes.lz4"), null, true);
		assertEquals(expected, uncommitted);
		RepoChangeListings.Snapshot uncommittedSnapshot = RepoChangeListings
				.acquire(RepoChangeListings.resolveGitRoot(gitRoot));
		assertTrue(uncommittedSnapshot != null, "git state should be available");
		assertEquals(uncommitted, NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, projectRoot, roots,
				tempDir.resolve("nested-multi-root-hashes.lz4"), null, true, uncommittedSnapshot));

		git(gitRoot, "add", ".");
		git(gitRoot, "commit", "-q", "-m", "native changes");
		Set<String> sinceLastCommit = NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot,
				roots, tempDir.resolve("nested-multi-root-hashes.lz4"), null, true);
		assertEquals(expected, sinceLastCommit);
		RepoChangeListings.Snapshot committedSnapshot = RepoChangeListings
				.acquire(RepoChangeListings.resolveGitRoot(gitRoot));
		assertTrue(committedSnapshot != null, "git state should be available");
		assertEquals(sinceLastCommit, NativeChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot,
				roots, tempDir.resolve("nested-multi-root-hashes.lz4"), null, true, committedSnapshot));
	}

	@Test
	void nativeRenameReportsOnlyDestinationForDirectAndSnapshotDetection() throws Exception {
		Path root = tempDir.resolve("rename-repo");
		Path oldFile = root.resolve("n/old.cpp");
		Files.createDirectories(oldFile.getParent());
		git(root, "init", "-q");
		Files.writeString(oldFile, "int value;", StandardCharsets.UTF_8);
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");
		git(root, "mv", "n/old.cpp", "n/new.cpp");

		Path hashFile = tempDir.resolve("rename-hashes.lz4");
		Set<String> direct = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), hashFile, null, false);
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(root));
		assertTrue(snapshot != null, "git state should be available");
		Set<String> cached = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), hashFile, null, true, snapshot);

		assertEquals(Set.of("n/new.cpp"), direct);
		assertEquals(direct, cached);
		assertTrue(snapshot.statusPorcelainPaths().containsAll(List.of("n/old.cpp", "n/new.cpp")),
				"general status consumers retain both rename endpoints");
	}

	@Test
	void nulDelimitedGitStatusPreservesQuotedAndControlCharacterPaths() throws Exception {
		Path root = tempDir.resolve("literal-path-repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		List<String> names = List.of("n/ünïcode.cpp", "n/quote\"name.cpp", "n/line\nbreak.cpp", "n/arrow -> name.cpp");
		for (String name : names) {
			Path file = root.resolve(name);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "int before;", StandardCharsets.UTF_8);
		}
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");
		git(root, "config", "core.quotePath", "true");
		for (String name : names) {
			Files.writeString(root.resolve(name), "int after;", StandardCharsets.UTF_8);
		}

		Set<String> expected = Set.copyOf(names);
		Set<String> direct = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), tempDir.resolve("literal-paths.lz4"), null, true);
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(root));
		assertTrue(snapshot != null, "git state should be available");
		Set<String> cached = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("n")), tempDir.resolve("literal-paths.lz4"), null, true, snapshot);

		assertEquals(expected, direct);
		assertEquals(direct, cached);
	}

	@Test
	void nativeRootEqualToProjectRootProducesRelativeKeys() throws Exception {
		Path root = tempDir.resolve("project-root-native-repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		Path source = write(root.resolve("root.cpp"), "int value;");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");
		Files.writeString(source, "int changed;", StandardCharsets.UTF_8);

		Set<String> direct = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root, List.of(root),
				tempDir.resolve("root-native-hashes.lz4"), null, true);
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(root));
		Set<String> cached = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root, List.of(root),
				tempDir.resolve("root-native-hashes.lz4"), null, true, snapshot);
		assertEquals(Set.of("root.cpp"), direct);
		assertEquals(direct, cached);
	}

	@Test
	void gitListingIsAcquiredOnceForMultipleNativeRoots() throws Exception {
		Path root = tempDir.resolve("multi-root-repo");
		Files.createDirectories(root);
		git(root, "init", "-q");
		write(root.resolve("one/a.cpp"), "int a;");
		write(root.resolve("two/b.cpp"), "int b;");
		git(root, "add", ".");
		git(root, "commit", "-q", "-m", "initial");
		Files.writeString(root.resolve("one/a.cpp"), "int aa;", StandardCharsets.UTF_8);
		Files.writeString(root.resolve("two/b.cpp"), "int bb;", StandardCharsets.UTF_8);

		long before = GitSupport.spawnCount();
		Set<String> changed = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("one"), root.resolve("two")), tempDir.resolve("multi-root-hashes.lz4"), null,
				true);

		assertEquals(Set.of("one/a.cpp", "two/b.cpp"), changed);
		assertEquals(1, GitSupport.spawnCount() - before, "one repo-wide status listing should serve both roots");

		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(root));
		assertTrue(snapshot != null, "git state should be available");
		snapshot.statusRecords();
		before = GitSupport.spawnCount();
		Set<String> cached = NativeChangeDetector.detect(ChangeDetector.Mode.UNCOMMITTED, root,
				List.of(root.resolve("one"), root.resolve("two")), tempDir.resolve("multi-root-hashes.lz4"), null, true,
				snapshot);
		assertEquals(changed, cached);
		assertEquals(0, GitSupport.spawnCount() - before, "same-root snapshot paths must be reused in memory");
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
