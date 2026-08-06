package io.metaloom.loom.api.pipeline;

/**
 * The state of one {@code pipeline_node_task} — one node execution against one media item.
 *
 * <p>
 * Close to, but deliberately not the same as, {@code io.metaloom.loom.pipeline.model.NodeState}: the
 * engine has no {@link #DEAD_LETTER}, because giving up on a task is a persistence decision made by
 * the reaper rather than an outcome a worker can report. The two are mapped explicitly, never with
 * {@code valueOf(name())}.
 * </p>
 */
public enum NodeTaskState {

	/** Not yet dispatched, or dispatched to nobody because no worker would take it. */
	PENDING,

	/** Leased by a worker, result not yet received. */
	RUNNING,

	/** Finished successfully. */
	COMPLETED,

	/** Finished unsuccessfully; may still be retried while attempts remain. */
	FAILED,

	/** Not executed — dry run, failed blocking dependency, or filter branch mismatch. */
	SKIPPED,

	/** Out of attempts, or orphaned by a run that no longer exists. Never retried. */
	DEAD_LETTER;

	/** @return true for COMPLETED, FAILED, SKIPPED and DEAD_LETTER */
	public boolean isTerminal() {
		return this != PENDING && this != RUNNING;
	}

	/**
	 * Parse a persisted or wire value.
	 *
	 * @param column where the value came from, named in the failure message
	 * @param value  the raw value; {@code null} parses to {@code null}
	 * @throws IllegalArgumentException when the value is not one of the six states
	 */
	public static NodeTaskState parse(String column, String value) {
		return PipelineVocabulary.parse(NodeTaskState.class, column, value);
	}
}
