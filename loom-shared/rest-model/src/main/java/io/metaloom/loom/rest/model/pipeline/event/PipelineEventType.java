package io.metaloom.loom.rest.model.pipeline.event;

/**
 * Types of pipeline tracking events dispatched over the pipeline WebSocket.
 * These events allow UI clients to visualise pipeline execution in real time
 * and identify bottlenecks.
 */
public enum PipelineEventType {

	// ── Pipeline lifecycle ──────────────────────────────────────────────

	/** A pipeline has started processing a media batch. */
	PIPELINE_STARTED,

	/** A pipeline has finished processing all media items. */
	PIPELINE_COMPLETED,

	/**
	 * An in-flight run was suspended.
	 *
	 * <p>
	 * Emitted by the pause route rather than by the engine, because a pause is an operator decision and not something the run discovers about itself. A
	 * client that did not issue the pause - a second browser tab, the CLI - learns about it here, which is what lets every open editor agree on whether
	 * the control should read "Pause" or "Resume".
	 * </p>
	 */
	RUN_PAUSED,

	/** A suspended run was resumed. The counterpart of {@link #RUN_PAUSED}. */
	RUN_RESUMED,

	// ── Per-node / per-media events ─────────────────────────────────────

	/** A media item has entered a node and processing has begun. */
	NODE_STARTED,

	/** A node has successfully finished processing a media item. */
	NODE_COMPLETED,

	/** A node failed while processing a media item. */
	NODE_FAILED,

	/** A node was skipped (filter branch mismatch, dependency failure, dry-run). */
	NODE_SKIPPED,

	/** A media item is buffered/queued at a node because concurrency limit is reached. */
	NODE_BUFFERED,

	/**
	 * A breakpoint is withholding one completed execution from its dependents.
	 *
	 * <p>
	 * Sent immediately rather than folded into the {@link #NODE_STATS} tick, for the same reason
	 * {@link #NODE_FAILED} is: a hold happens because a person asked for it, is individually
	 * actionable, and is worthless a second late. The frame carries the node, the item and the
	 * element sequence, so the editor can ring the right node and open the result that stopped it.
	 * </p>
	 */
	NODE_BREAKPOINT_HELD,

	/** A withheld execution was let through, by Continue, by Step, or by disarming the breakpoint. */
	NODE_BREAKPOINT_RELEASED,

	// ── Periodic aggregate stats ────────────────────────────────────────

	/** Periodic per-node throughput snapshot (active, pending, processed, failed counts). */
	NODE_STATS
}
