package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Coverage-closure support for test selection: computes the changed-element
 * universe (changed PRODUCTION classes only, with member-level precision via
 * {@link ChangedMembers} when available), the per-test coverage sets over that
 * universe, the greedy {@link SetCoverComputer} cover core, and the
 * sole-coverer map used to exempt tests from cache-skip.
 *
 * <p>
 * The universe is <em>production-only</em> by contract: changed test classes
 * never enter the universe and never cover themselves. They are already handled
 * by selection's changed-test path (scored as changed, capped by topN), and the
 * static call-graph expansion can pull test classes into
 * {@link ChangedMembers#changedClasses()} as callers of changed production code
 * — those entries are filtered out here. A test class may still be a
 * <em>coverer</em> of production elements (via its dependency edges); it just
 * cannot be a universe element.
 *
 * <p>
 * The universe is <em>class-granular</em> (changed production classes ∪ changed
 * production member classes); member-level precision enters through the
 * coverage sets: {@link StructuralChangeAnalyzer#computeOverlapClasses} matches
 * a test's {@code Class#member} dependencies against the specific changed
 * members, so a test only "covers" a changed class when it actually exercises a
 * changed member (MEMBER instrumentation), or at plain class level as fallback.
 * This mirrors the granularity already used by {@code TestScorer}'s set-cover
 * bonus.
 *
 * <p>
 * Contract (see notes/tia-contract.md): the closure only ever ADDS tests to a
 * selection; the cover core must survive topN capping and cache-skip; the
 * universe honors includePackages/module filtering because the effective
 * (module-filtered) dep map restricts both candidates and coverage sets.
 */
public final class CoverageClosure {

	/**
	 * Which universe elements the finally-selected subset exercises.
	 *
	 * @param totalElements
	 *            size of the universe (X/Y: the Y)
	 * @param coveredCount
	 *            universe elements covered by at least one selected test
	 * @param uncoveredElements
	 *            universe elements not covered by any selected test, in universe
	 *            order
	 */
	public record Status(int totalElements, int coveredCount, List<String> uncoveredElements) {
		/** True when every universe element is exercised by the selected subset. */
		public boolean allCovered() {
			return uncoveredElements.isEmpty();
		}
	}

	private static final Status EMPTY_STATUS = new Status(0, 0, List.of());

	private final Set<String> universe;
	/** test class → universe elements it covers (non-empty entries only). */
	private final Map<String, Set<String>> coverage;
	/** Greedy minimal-ish cover core in pick order. */
	private final List<String> coverCore;
	/** Tests that are the ONLY coverer of at least one universe element. */
	private final Set<String> soleCovererTests;
	/**
	 * Member-level universe but class-level coverage sets (MEMBER instrumentation
	 * off).
	 */
	private final boolean memberPrecisionUnavailable;

	private CoverageClosure(Set<String> universe, Map<String, Set<String>> coverage,
			boolean memberPrecisionUnavailable) {
		this.universe = universe;
		this.coverage = coverage;
		if (universe.isEmpty()) {
			this.coverCore = List.of();
			this.soleCovererTests = Set.of();
		} else {
			SetCoverComputer.Result<String> result = new SetCoverComputer<>(coverage, universe).compute();
			this.coverCore = List.copyOf(result.order());
			// element → coverers; a test is a "sole coverer" when it is the only
			// candidate covering some universe element. Sole coverers are always part
			// of the cover core (the greedy cover cannot avoid them), but the explicit
			// set documents the cache-skip exemption as a hard invariant.
			Map<String, List<String>> coverersByElement = new LinkedHashMap<>();
			for (var e : coverage.entrySet()) {
				for (String element : e.getValue()) {
					coverersByElement.computeIfAbsent(element, ignored -> new ArrayList<>()).add(e.getKey());
				}
			}
			Set<String> sole = new LinkedHashSet<>();
			for (var e : coverersByElement.entrySet()) {
				if (e.getValue().size() == 1) {
					sole.add(e.getValue().get(0));
				}
			}
			this.soleCovererTests = Set.copyOf(sole);
		}
		this.memberPrecisionUnavailable = memberPrecisionUnavailable;
	}

	/**
	 * Computes the closure view for one selection run.
	 *
	 * @param depMap
	 *            the effective (module/includePackages-filtered) dependency map
	 * @param changedClasses
	 *            class-level changed production classes (may be empty; defensive
	 *            test-class entries are filtered out)
	 * @param changedTestClasses
	 *            changed test classes (changed ∪ new from the caller). Accepted for
	 *            call-site compatibility but deliberately NOT part of the universe:
	 *            a changed test class is already handled by selection's
	 *            changed-test path (scored as changed, capped by topN) and must not
	 *            force-include itself via the cover core or count as an element
	 *            needing coverage.
	 * @param changedMembers
	 *            member-level change analysis, or {@code null} for the
	 *            since-last-run class-level fallback. Note: the static call-graph
	 *            expansion can add test classes to
	 *            {@link ChangedMembers#changedClasses()} (test bytecode is scanned
	 *            for callers of changed production code) — those entries are
	 *            filtered out below.
	 */
	public static CoverageClosure compute(DependencyMap depMap, Set<String> changedClasses,
			Set<String> changedTestClasses, ChangedMembers changedMembers) {
		Set<String> knownTests = depMap.testClasses();
		Set<String> universe = new LinkedHashSet<>();
		if (changedClasses != null) {
			for (String c : changedClasses) {
				if (!isTestClassName(c, knownTests)) {
					universe.add(c);
				}
			}
		}
		if (changedMembers != null) {
			for (String c : changedMembers.changedClasses()) {
				if (!isTestClassName(c, knownTests)) {
					universe.add(c);
				}
			}
		}
		Set<String> indexedChangedTests = new LinkedHashSet<>();
		if (changedTestClasses != null) {
			for (String t : changedTestClasses) {
				if (knownTests.contains(t)) {
					indexedChangedTests.add(t);
				}
			}
		}
		if (universe.isEmpty()) {
			return new CoverageClosure(Set.of(), Map.of(), false);
		}

		// Candidates: tests reachable via the inverted index (handles the
		// nested-class dep aliasing), plus indexed changed test classes — a changed
		// test can still COVER production elements (via dep/member edges), it just
		// never enters the universe and never covers itself.
		Set<String> candidates = new LinkedHashSet<>(depMap.getAffectedTests(universe));
		candidates.addAll(indexedChangedTests);

		boolean hasMemberDeps = depMap.hasMemberDeps();
		Map<String, Set<String>> coverage = new LinkedHashMap<>();
		for (String test : candidates) {
			Set<String> deps = depMap.get(test);
			Set<String> memberDeps = hasMemberDeps ? depMap.getMemberDeps(test) : null;
			Set<String> raw = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers,
					universe);
			Set<String> covered = new LinkedHashSet<>();
			for (String dep : raw) {
				String element = resolveToUniverse(dep, universe);
				if (element != null) {
					covered.add(element);
				}
			}
			if (!covered.isEmpty()) {
				coverage.put(test, covered);
			}
		}

		// Precision notice data: the universe was derived from member-level change
		// data, but the index has no MEMBER instrumentation, so coverage sets degrade
		// to class-level overlap (computeOverlapClasses fallback branch).
		boolean memberPrecisionUnavailable = changedMembers != null && !changedMembers.changedMemberKeys().isEmpty()
				&& !hasMemberDeps;
		// keep universe order for deterministic uncovered-element reporting
		return new CoverageClosure(Collections.unmodifiableSet(universe), coverage, memberPrecisionUnavailable);
	}

	/**
	 * True when {@code className} is a test class of the effective dep map or an
	 * inner class of one (nested {@code Outer$Inner} form). The dep map's keys are
	 * test classes, so this is the reliable in-module classification — the same
	 * rule {@code CoverageOperation} uses to exclude test classes from coverage
	 * analysis.
	 */
	private static boolean isTestClassName(String className, Set<String> testClassNames) {
		if (testClassNames.contains(className)) {
			return true;
		}
		int dollar = className.indexOf('$');
		return dollar > 0 && testClassNames.contains(className.substring(0, dollar));
	}

	/**
	 * Maps a test-side dependency name (possibly a nested class, e.g.
	 * {@code com.Foo$Bar}) to the universe element it belongs to, or {@code null}
	 * when it matches no universe element.
	 */
	private static String resolveToUniverse(String depName, Set<String> universe) {
		if (universe.contains(depName)) {
			return depName;
		}
		int dollar = depName.indexOf('$');
		if (dollar > 0) {
			String top = depName.substring(0, dollar);
			if (universe.contains(top)) {
				return top;
			}
		}
		for (String element : universe) {
			// universe element is a nested class of the dep name (rare: nested FQCN
			// reported as changed while tests depend on the top-level class)
			if (DependencyMap.changedClassesContains(Set.of(depName), element)) {
				return element;
			}
		}
		return null;
	}

	/** True when there is no universe (no change signal) — closure is inert. */
	public boolean isEmpty() {
		return universe.isEmpty();
	}

	/** The changed-element universe (unmodifiable, universe order). */
	public Set<String> universe() {
		return universe;
	}

	/**
	 * The greedy cover core in pick order: the minimal-ish test set covering the
	 * whole universe. Tests in this set must be force-included BEFORE topN cap
	 * counting and are exempt from cache-skip.
	 */
	public List<String> coverCore() {
		return coverCore;
	}

	/**
	 * Tests that are the sole coverer of at least one universe element. These are
	 * exempt from cache-skip: dropping one would leave a changed element without
	 * any exercising test.
	 */
	public Set<String> soleCovererTests() {
		return soleCovererTests;
	}

	/** Member-level universe but only class-level coverage sets available. */
	public boolean memberPrecisionUnavailable() {
		return memberPrecisionUnavailable;
	}

	/**
	 * Computes which universe elements the given (final) test selection exercises.
	 *
	 * @param selectedTests
	 *            the finally selected test classes (post cap, post cache-skip)
	 */
	public Status status(Collection<String> selectedTests) {
		if (universe.isEmpty()) {
			return EMPTY_STATUS;
		}
		Set<String> covered = new LinkedHashSet<>();
		for (String test : selectedTests) {
			covered.addAll(coverage.getOrDefault(test, Set.of()));
		}
		List<String> uncovered = new ArrayList<>();
		for (String element : universe) {
			if (!covered.contains(element)) {
				uncovered.add(element);
			}
		}
		return new Status(universe.size(), universe.size() - uncovered.size(), List.copyOf(uncovered));
	}
}
