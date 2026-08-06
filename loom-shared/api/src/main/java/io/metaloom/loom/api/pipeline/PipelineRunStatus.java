package io.metaloom.loom.api.pipeline;

/**
 * The lifecycle status of a {@code pipeline_run}.
 *
 * <p>
 * The column stays {@code VARCHAR}: a Postgres enum needs a migration for every new value, and this
 * vocabulary is read far more often than it grows. The type safety is bought at the Java boundary
 * instead — {@link #parse(String, String)} on the way in, {@link #name()} on the way out.
 * </p>
 */
public enum PipelineRunStatus {

	/** Created, not yet dispatched to a worker. */
	PENDING,

	/** In flight. */
	RUNNING,

	/**
	 * Suspended by an operator.
	 *
	 * <p>
	 * Deliberately <em>not</em> terminal: a paused run is still live, still holds an engine, and can
	 * be resumed or cancelled.
	 * </p>
	 */
	PAUSED,

	/** Every media item that was processed succeeded — including a run that processed nothing. */
	SUCCESS,

	/** Every media item failed, or the run never got off the ground. */
	FAILED,

	/** Some media items failed and some did not. */
	PARTIAL,

	/** Stopped by an operator before it finished. */
	CANCELLED;

	/**
	 * Whether no further status change is expected.
	 *
	 * <p>
	 * A run in a terminal status must not be overwritten by a late-arriving completion or timeout
	 * report. {@link #PAUSED} is not terminal.
	 * </p>
	 */
	public boolean isTerminal() {
		return this == SUCCESS || this == FAILED || this == PARTIAL || this == CANCELLED;
	}

	/**
	 * Parse a persisted or wire value.
	 *
	 * @param column where the value came from, named in the failure message so an operator knows which
	 *               row to look at
	 * @param value  the raw value; {@code null} parses to {@code null}
	 * @throws IllegalArgumentException when the value is not one of the seven statuses
	 */
	public static PipelineRunStatus parse(String column, String value) {
		return PipelineVocabulary.parse(PipelineRunStatus.class, column, value);
	}
}
