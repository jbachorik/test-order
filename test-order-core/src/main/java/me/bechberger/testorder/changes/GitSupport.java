package me.bechberger.testorder.changes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shared git subprocess utilities used by {@link GitChangeDetector} and
 * {@link StructuralDiff}.
 */
final class GitSupport {

	private GitSupport() {
	}

	/**
	 * Monotonic count of git subprocesses spawned through this class. Test seam:
	 * lets tests assert that snapshot-based filtering performs zero git spawns (a
	 * PATH-shim git wrapper cannot intercept {@code ProcessBuilder("git")} inside
	 * the JVM, since the process environment is immutable).
	 */
	private static final java.util.concurrent.atomic.AtomicLong SPAWN_COUNT = new java.util.concurrent.atomic.AtomicLong();

	static long spawnCount() {
		return SPAWN_COUNT.get();
	}

	/**
	 * Runs a git command in {@code workDir} and returns its stdout lines (stripped
	 * of trailing whitespace, empty lines excluded).
	 *
	 * @param throwOnError
	 *            when {@code true}, throws {@link IOException} on timeout, non-zero
	 *            exit, or interruption; when {@code false}, returns an empty list
	 *            instead
	 */
	static List<String> runGit(Path workDir, boolean throwOnError, String... args) throws IOException {
		byte[] output = runGitBytes(workDir, throwOnError, args);
		List<String> lines = new ArrayList<>();
		for (String line : new String(output, StandardCharsets.UTF_8).lines().toList()) {
			String trimmed = line.stripTrailing();
			if (!trimmed.isEmpty()) {
				lines.add(trimmed);
			}
		}
		return lines;
	}

	/**
	 * Runs git and returns the merged output bytes without line splitting. This is
	 * required for commands such as {@code status -z}, whose path fields can
	 * contain newlines and other characters that line-oriented output cannot
	 * preserve.
	 */
	static byte[] runGitBytes(Path workDir, boolean throwOnError, String... args) throws IOException {
		return runGitBytes(workDir, throwOnError, ProcessBuilder::start, args);
	}

	@FunctionalInterface
	interface ProcessStarter {
		Process start(ProcessBuilder builder) throws IOException;
	}

	static byte[] runGitBytes(Path workDir, boolean throwOnError, ProcessStarter processStarter, String... args)
			throws IOException {
		SPAWN_COUNT.incrementAndGet();
		List<String> command = new ArrayList<>();
		command.add("git");
		Collections.addAll(command, args);

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir.toFile());
		pb.redirectErrorStream(true);
		// stdin is a pipe, never a Redirect.DISCARD: DISCARD is a WRITE-type
		// redirect and redirectInput rejects it outright. The pipe is closed
		// immediately after start so any git command reading stdin sees EOF.
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		Thread drainer = null;
		boolean waitCompleted = false;
		Process process = processStarter.start(pb);
		try {
			try {
				process.getOutputStream().close();
			} catch (IOException ignored) {
			}
			// Drain the merged output on a background thread so waitFor() can honour its
			// timeout even when git emits enough data to fill the pipe.
			drainer = new Thread(() -> {
				try (var input = process.getInputStream()) {
					input.transferTo(captured);
				} catch (IOException ignored) {
				}
			}, "test-order-git-drain");
			drainer.setDaemon(true);
			drainer.start();

			if (!process.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				if (process.isAlive()) {
					process.destroyForcibly();
				}
				drainer.interrupt();
				if (throwOnError) {
					throw new IOException(
							"git command timed out after " + GitTimeout.seconds() + "s: " + String.join(" ", command));
				}
				return new byte[0];
			}
			waitCompleted = true;
			drainer.join(2000);
			byte[] output = captured.toByteArray();
			if (process.exitValue() != 0) {
				if (throwOnError) {
					throw new IOException(
							"git command failed: " + String.join(" ", command) + summarizeError(decodeLines(output)));
				}
				return new byte[0];
			}
			return output;
		} catch (InterruptedException e) {
			if (!waitCompleted && process.isAlive()) {
				process.destroyForcibly();
			}
			if (drainer != null) {
				drainer.interrupt();
			}
			Thread.currentThread().interrupt();
			if (throwOnError) {
				throw new IOException("git command interrupted: " + String.join(" ", command), e);
			}
			return new byte[0];
		} finally {
			// A normally exited process (including a nonzero exit) is left alone. Any
			// exceptional path before wait completion must not leak a live child.
			if (!waitCompleted && process.isAlive()) {
				process.destroyForcibly();
			}
			if (drainer != null && drainer.isAlive()) {
				drainer.interrupt();
			}
			try {
				process.getOutputStream().close();
			} catch (IOException ignored) {
			}
			try {
				process.getInputStream().close();
			} catch (IOException ignored) {
			}
		}
	}

	private static List<String> decodeLines(byte[] output) {
		return new String(output, StandardCharsets.UTF_8).lines().map(String::stripTrailing)
				.filter(line -> !line.isEmpty()).toList();
	}

	private static String summarizeError(List<String> lines) {
		if (lines.isEmpty()) {
			return "";
		}
		String primary = lines.stream().filter(line -> !line.toLowerCase(Locale.ROOT).startsWith("usage:")).findFirst()
				.orElse(lines.get(0));
		if (primary.length() > 300) {
			primary = primary.substring(0, 300) + "...";
		}
		int additionalLines = lines.size() - 1;
		if (additionalLines <= 0) {
			return " — " + primary;
		}
		return " — " + primary + " (" + additionalLines + " more line" + (additionalLines == 1 ? "" : "s") + ")";
	}
}
