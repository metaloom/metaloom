package io.metaloom.loom.api.pipeline;

/**
 * Where a {@code pipeline_run} got its definition from.
 *
 * <p>
 * The discriminator exists so that no consumer has to infer intent from {@code pipeline_uuid IS
 * NULL}. That test would also be true of a run whose pipeline was hard-deleted; this one states what
 * the run was created as and survives it.
 * </p>
 *
 * <p>
 * The column stays {@code VARCHAR} for the same reason {@link PipelineRunStatus} does, and the
 * pairing with {@code pipeline_uuid} is enforced by a CHECK constraint in
 * {@code V2.82__adhoc_pipeline_run.sql}, so the impossible third state cannot be stored.
 * </p>
 */
public enum PipelineRunKind {

	/** Started from a stored pipeline row; {@code pipeline_uuid} and {@code pipeline_version} name it. */
	PIPELINE,

	/**
	 * Started from a definition submitted with the request.
	 *
	 * <p>
	 * {@code pipeline_uuid} is {@code null} and the definition lives in {@code meta.definition}. These
	 * runs are addressed under {@code /api/v1/node-runs}, are scoped to their creator, and are excluded
	 * from the pipeline run statistics - see {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.
	 * </p>
	 */
	ADHOC;

	/**
	 * Parse a persisted or wire value.
	 *
	 * @param column where the value came from, named in the failure message so an operator knows which
	 *               row to look at
	 * @param value  the raw value; {@code null} and blank both parse to {@code null}
	 * @throws IllegalArgumentException when the value is neither kind
	 */
	public static PipelineRunKind parse(String column, String value) {
		return PipelineVocabulary.parse(PipelineRunKind.class, column, value);
	}
}
