package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuralDiffGitTest {

	@TempDir
	Path tempDir;

	@Test
	void diffSinceLastCommitHandlesMissingHeadParent() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"),
				"package com.example; public class Foo { int compute() { return 1; } }\n");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(srcDir.resolve("Foo.java"),
				"package com.example; public class Foo { int compute() { return 2; } }\n");

		List<StructuralDiff.FileDiff> diffs = assertDoesNotThrow(() -> StructuralDiff.diffSinceLastCommit(tempDir));
		boolean hasAddedType = diffs.stream().flatMap(diff -> diff.changes().stream())
				.anyMatch(change -> change.kind() == StructuralDiff.Change.Kind.ADDED
						&& change.category() == StructuralDiff.Change.Category.TYPE && change.name().equals("Foo"));
		assertFalse(hasAddedType,
				"modified tracked file should not appear as a newly added type when HEAD~1 is missing");
	}

	private void git(String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
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
		pb.directory(tempDir.toFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		Thread drainer = null;
		try {
			try {
				p.getOutputStream().close();
			} catch (IOException ignored) {
			}
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			drainer = new Thread(() -> {
				try (var input = p.getInputStream()) {
					input.transferTo(captured);
				} catch (IOException ignored) {
				}
			}, "test-order-test-git-drain");
			drainer.setDaemon(true);
			drainer.start();
			if (!p.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				throw new IOException(
						"git command timed out after " + GitTimeout.seconds() + "s: " + String.join(" ", args));
			}
			int exitCode = p.exitValue();
			if (exitCode != 0) {
				throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} finally {
			if (p.isAlive()) {
				p.destroyForcibly();
			}
			if (drainer != null && drainer.isAlive()) {
				drainer.interrupt();
			}
			try {
				p.getOutputStream().close();
			} catch (IOException ignored) {
			}
			try {
				p.getInputStream().close();
			} catch (IOException ignored) {
			}
			try {
				p.getErrorStream().close();
			} catch (IOException ignored) {
			}
		}
	}
}
