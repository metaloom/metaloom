package io.metaloom.loom.api.pipeline;

/**
 * The state of one {@code pipeline_run_item} — one media item discovered by a run's source node.
 *
 * <p>
 * The failure value is {@link #FAILED}, matching the column comment, the terminal-state query in
 * {@code PipelineRunItemDaoImpl} and the run-level vocabulary. The engine's own outcome enum spells
 * it {@code FAILURE}; that difference used to reach the database verbatim, which left every failed
 * item looking unfinished. It is mapped explicitly now.
 * </p>
 */
public enum RunItemState {

	/** Discovered, no node has run against it yet. */
	PENDING,

	/** At least one node is in flight for this item. */
	RUNNING,

	/** Every node that had to run against the item succeeded. */
	SUCCESS,

	/** A node failed and the item could not be completed. */
	FAILED,

	/** Not processed — dry run, or filtered out before any work happened. */
	SKIPPED;

	/** @return true for SUCCESS, FAILED and SKIPPED */
	public boolean isTerminal() {
		return this == SUCCESS || this == FAILED || this == SKIPPED;
	}

	/**
	 * Parse a persisted or wire value.
	 *
	 * @param column where the value came from, named in the failure message
	 * @param value  the raw value; {@code null} parses to {@code null}
	 * @throws IllegalArgumentException when the value is not one of the five states
	 */
	public static RunItemState parse(String column, String value) {
		return PipelineVocabulary.parse(RunItemState.class, column, value);
	}
}
