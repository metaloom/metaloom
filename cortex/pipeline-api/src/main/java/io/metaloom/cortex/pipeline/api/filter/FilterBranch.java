package io.metaloom.cortex.pipeline.api.filter;

/**
 * Specifies which branch of a filter node a downstream node depends on.
 * Filter nodes produce a {@code filter_passed} boolean output. Downstream
 * nodes can declare a conditional dependency on either the PASS or REJECT
 * branch, or on ANY branch (unconditional — the default).
 */
public enum FilterBranch {

	/**
	 * The downstream node should only execute if the filter passed.
	 */
	PASS,

	/**
	 * The downstream node should only execute if the filter rejected.
	 */
	REJECT,

	/**
	 * The downstream node executes regardless of the filter outcome.
	 */
	ANY
}
