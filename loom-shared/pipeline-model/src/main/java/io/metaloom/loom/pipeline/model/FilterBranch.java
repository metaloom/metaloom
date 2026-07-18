package io.metaloom.loom.pipeline.model;

/**
 * Which branch of a filter node a downstream node is wired to.
 *
 * <p>A filter node writes {@link #FILTER_PASSED} into its outputs. A downstream
 * node that declares a conditional dependency on that filter only runs when the
 * recorded value matches the branch it is wired to.</p>
 *
 * <p>Mirrors {@code io.metaloom.cortex.pipeline.api.filter.FilterBranch}.</p>
 */
public enum FilterBranch {

	/** Run regardless of the upstream filter outcome. The default for a plain edge. */
	ANY,

	/** Run only when the upstream filter passed the item. */
	PASS,

	/** Run only when the upstream filter rejected the item. */
	REJECT;

	/** Output key a filter node writes its verdict to. */
	public static final String FILTER_PASSED = "filter_passed";

	/**
	 * Whether this branch admits the given filter verdict.
	 *
	 * @param filterPassed the upstream {@code filter_passed} value, may be null when the
	 *                     upstream node is not a filter or produced no verdict
	 * @return true when the downstream node should run
	 */
	public boolean admits(Boolean filterPassed) {
		switch (this) {
			case ANY:
				return true;
			case PASS:
				return Boolean.TRUE.equals(filterPassed);
			case REJECT:
				return Boolean.FALSE.equals(filterPassed);
			default:
				return true;
		}
	}
}
