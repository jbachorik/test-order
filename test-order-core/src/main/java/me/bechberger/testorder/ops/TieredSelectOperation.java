package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import me.bechberger.testorder.CoverageClosure;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TieredTestSelector;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Framework-agnostic tiered test selection orchestration. Runs
 * {@link TieredTestSelector} and writes the tier files to disk.
 *
 * <p>
 * Three-tiered CI execution:
 * <ol>
 * <li>Tier 1: change-affected tests (dep overlap, new,
 * changed, @AlwaysRun)</li>
 * <li>Tier 2: top-scored remaining tests (configurable fraction by
 * duration)</li>
 * <li>Tier 3: everything else</li>
 * </ol>
 */
public final class TieredSelectOperation {

	private TieredSelectOperation() {
	}

	/** Input configuration for tiered test selection. */
	public record TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
			boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
			PluginLog log, ChangedMembers changedMembers, boolean coverageEnforce) {

		/** Backward-compatible constructor without coverage-closure wiring. */
		public TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
				Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
				boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
				PluginLog log) {
			this(depMap, state, changedClasses, changedTests, weights, tier2Fraction, weightByDuration,
					alwaysRunClasses, tier1File, tier2File, tier3File, log, null, false);
		}
	}

	/** Result of tiered test selection. */
	public record TieredSelectResult(TieredTestSelector.TieredSelection selection) {

		public boolean tier1Only() {
			return selection.tier2().isEmpty() && selection.tier3().isEmpty();
		}

		public int totalTests() {
			return selection.tier1().size() + selection.tier2().size() + selection.tier3().size();
		}
	}

	/**
	 * Runs tiered test selection and writes tier files to disk.
	 *
	 * <p>
	 * Coverage closure: the changed-element universe (changed classes ∪ changed
	 * members, member-level via {@link ChangedMembers} when available) must be
	 * exercised by the tier-1 set — tier 1 is the gate for tiers 2/3, so an
	 * uncovered changed element must be surfaced here. Uncovered elements are
	 * WARNed; with {@code coverageEnforce} the selection fails before the tier
	 * files are written (a failed-closure run never leaves tier files a subsequent
	 * CI step would consume).
	 *
	 * @throws IOException
	 *             if writing tier files fails
	 */
	public static TieredSelectResult select(TieredSelectConfig config) throws IOException {
		CoverageClosure closure = CoverageClosure.compute(config.depMap(), config.changedClasses(),
				config.changedTests(), config.changedMembers());
		TieredTestSelector.TieredSelection selection = new TieredTestSelector(config.depMap(), config.state(),
				config.changedClasses(), config.changedTests(), config.weights(),
				new TieredTestSelector.Config(config.tier2Fraction(), config.weightByDuration()),
				config.alwaysRunClasses(), closure).select();

		// ── Tier-1 closure outcome: WARN on uncovered changed elements; with
		// coverageEnforce the selection fails with an actionable message instead.
		CoverageClosure.Status status = selection.coverageStatus();
		String coverageSummary = "";
		if (status != null && status.totalElements() > 0) {
			coverageSummary = "; changed elements covered by tier 1: " + status.coveredCount() + "/"
					+ status.totalElements()
					+ (status.uncoveredElements().isEmpty()
							? ""
							: " (+" + status.uncoveredElements().size() + " uncovered)");
			if (!status.allCovered()) {
				List<String> uncovered = status.uncoveredElements();
				String fullList = String.join(", ", uncovered);
				String summaryLine = status.coveredCount() + "/" + status.totalElements()
						+ " changed elements are exercised by the tier-1 subset; " + uncovered.size() + " uncovered";
				if (config.coverageEnforce()) {
					throw new IllegalStateException("[test-order] Tier-1 coverage closure failed: " + summaryLine
							+ ". Uncovered: " + fullList + ". No tier-1 test exercises these changed elements — the "
							+ "dependency index may be stale (tests added/changed since the last learn phase). Run a "
							+ "learn phase to rebuild the index, plan to run tier 2/3 regardless of tier-1 results, "
							+ "or disable enforcement (-Dtestorder.coverage.enforce=false).");
				}
				config.log()
						.warn("[test-order] Tier-1 coverage closure: " + summaryLine + ". Uncovered: " + fullList
								+ ". Tier 1 is the gate for tiers 2/3 — consider a learn phase or running tier 2/3 "
								+ "regardless of tier-1 results.");
			}
		}

		TieredTestSelector.writeTierFiles(selection, config.tier1File(), config.tier2File(), config.tier3File());

		config.log()
				.info("[test-order] Tiered selection: " + selection.tier1().size() + " tier-1 (change-affected), "
						+ selection.tier2().size() + " tier-2 (top-scored), " + selection.tier3().size()
						+ " tier-3 (rest)" + coverageSummary);

		return new TieredSelectResult(selection);
	}
}
