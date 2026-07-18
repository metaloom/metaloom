package io.metaloom.cortex.pipeline.api;

/**
 * Context for a single pipeline execution, used to correlate the tracking
 * events emitted during that execution with the {@code pipeline_run} record
 * Loom created when it dispatched the work order.
 *
 * <p>Cortex may also run pipelines with no Loom involvement at all (offline
 * mode, CLI batch processing). In that case use {@link #none()}, which yields
 * a context with a {@code null} run id — tracking events are still emitted,
 * they simply carry no run correlation.</p>
 */
public final class PipelineRunContext {

	private static final PipelineRunContext NONE = new PipelineRunContext(null);

	private final String pipelineRunUuid;

	private PipelineRunContext(String pipelineRunUuid) {
		this.pipelineRunUuid = pipelineRunUuid;
	}

	/**
	 * A context with no associated Loom pipeline run.
	 */
	public static PipelineRunContext none() {
		return NONE;
	}

	/**
	 * A context correlated with the given Loom {@code pipeline_run} UUID.
	 *
	 * @param pipelineRunUuid the run UUID, may be {@code null} (equivalent to {@link #none()})
	 */
	public static PipelineRunContext of(String pipelineRunUuid) {
		return pipelineRunUuid == null || pipelineRunUuid.isBlank() ? NONE : new PipelineRunContext(pipelineRunUuid);
	}

	/**
	 * The Loom {@code pipeline_run} UUID this execution belongs to, or
	 * {@code null} when the run is not tracked by Loom.
	 */
	public String pipelineRunUuid() {
		return pipelineRunUuid;
	}

	/**
	 * Whether this execution is correlated with a Loom pipeline run.
	 */
	public boolean isTracked() {
		return pipelineRunUuid != null;
	}

	@Override
	public String toString() {
		return "PipelineRunContext{" + (pipelineRunUuid != null ? pipelineRunUuid : "untracked") + "}";
	}
}
