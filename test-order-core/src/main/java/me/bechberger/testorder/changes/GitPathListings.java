package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Shared repo-wide git path collection for source-specific change detectors.
 * Paths returned here remain git-root-relative; callers own their unit-key
 * convention.
 */
final class GitPathListings {

	private GitPathListings() {
	}

	/**
	 * Gets one complete listing for a git root and mode, then applies the caller's
	 * path predicate. Status renames/copies contribute their destination only;
	 * callers that need both endpoints can use the snapshot's general status API.
	 */
	static Set<String> changedPaths(Path gitRoot, ChangeDetector.Mode mode, RepoChangeListings.Snapshot shared,
			Predicate<String> includePath) throws IOException {
		if (matchesGitRoot(gitRoot, shared)) {
			try {
				return snapshotPaths(mode, shared, includePath);
			} catch (IOException | RuntimeException e) {
				// A stale or partially-computed snapshot must not cause one fallback per
				// source root. Fall through to one repo-wide direct listing.
			}
		}
		return directPaths(gitRoot, mode, includePath);
	}

	private static Set<String> snapshotPaths(ChangeDetector.Mode mode, RepoChangeListings.Snapshot shared,
			Predicate<String> includePath) throws IOException {
		return switch (mode) {
			case UNCOMMITTED -> selectStatus(shared.statusRecords(), includePath);
			case SINCE_LAST_COMMIT -> {
				RepoChangeListings.LastCommitData lastCommit = shared.lastCommit();
				Collection<String> paths = lastCommit.hasHeadParent && !lastCommit.diffFailed
						? lastCommit.diffPaths
						: shared.trackedPaths();
				if (!(lastCommit.hasHeadParent && !lastCommit.diffFailed)) {
					TestOrderLogger
							.warn("git revision HEAD~1 is unavailable; treating all tracked native files as changed");
				}
				yield selectLinePaths(paths, includePath);
			}
			default -> throw new AssertionError(mode);
		};
	}

	private static Set<String> directPaths(Path gitRoot, ChangeDetector.Mode mode, Predicate<String> includePath)
			throws IOException {
		return switch (mode) {
			case UNCOMMITTED -> selectStatus(RepoChangeListings.parseStatusZ(
					GitSupport.runGitBytes(gitRoot, true, "status", "--porcelain", "-z", "--untracked-files=all")),
					includePath);
			case SINCE_LAST_COMMIT -> {
				Collection<String> paths;
				try {
					paths = GitSupport.runGit(gitRoot, true, "diff", "--name-only", "HEAD~1", "HEAD");
				} catch (IOException e) {
					TestOrderLogger
							.warn("git revision HEAD~1 is unavailable; treating all tracked native files as changed");
					paths = GitSupport.runGit(gitRoot, true, "ls-files");
				}
				yield selectLinePaths(paths, includePath);
			}
			default -> throw new AssertionError(mode);
		};
	}

	private static Set<String> selectStatus(Collection<RepoChangeListings.StatusRecord> records,
			Predicate<String> includePath) {
		Set<String> selected = new TreeSet<>();
		for (RepoChangeListings.StatusRecord record : records) {
			String destination = record.path().replace('\\', '/');
			if (includePath.test(destination)) {
				selected.add(destination);
			}
		}
		return selected;
	}

	private static Set<String> selectLinePaths(Collection<String> paths, Predicate<String> includePath) {
		Set<String> selected = new TreeSet<>();
		for (String path : paths) {
			String normalized = RepoChangeListings.unquoteGitPath(path).replace('\\', '/');
			if (includePath.test(normalized)) {
				selected.add(normalized);
			}
		}
		return selected;
	}

	private static boolean matchesGitRoot(Path gitRoot, RepoChangeListings.Snapshot shared) {
		if (shared == null) {
			return false;
		}
		try {
			return shared.gitRoot().equals(gitRoot.toRealPath());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
