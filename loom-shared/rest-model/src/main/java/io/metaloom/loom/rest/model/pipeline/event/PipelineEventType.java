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

	// ── Periodic aggregate stats ────────────────────────────────────────

	/** Periodic per-node throughput snapshot (active, pending, processed, failed counts). */
	NODE_STATS
}
