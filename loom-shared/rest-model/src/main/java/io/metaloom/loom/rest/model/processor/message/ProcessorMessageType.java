package io.metaloom.loom.rest.model.processor.message;

/**
 * Types of messages exchanged over the processor WebSocket connection.
 */
public enum ProcessorMessageType {

	// --- Messages FROM processor TO loom ---

	/** Initial registration of a processor node */
	REGISTER,

	/**
	 * The node contracts this worker offers, sent once after {@code REGISTERED}.
	 *
	 * <p>Deliberately a second frame rather than a field on {@code REGISTER}: registration is a cheap
	 * synchronous in-memory operation and has to stay that way, while ingesting ~115 KB of contracts
	 * writes to Postgres. Putting the two on one path would turn a reconnect storm into a database
	 * problem. The window where a worker is online but its specs are not yet ingested is harmless —
	 * dispatch reads the whitelist, never the descriptor registry.</p>
	 */
	NODE_REGISTRATION,

	/** Heartbeat ping from processor */
	HEARTBEAT,

	/** System status update from processor */
	STATUS_UPDATE,

	/** State change notification from processor */
	STATE_CHANGE,

	/**
	 * Pipeline tracking event from a processor. <b>Accepted and dropped.</b>
	 *
	 * <p>Under Variant C, Loom owns the pipeline graph, so run events are emitted by
	 * {@code RunStatsAggregator} — counted per node and pushed as {@code NODE_STATS} on a
	 * timer, with failures released immediately. Relaying a worker frame to the UI socket
	 * would be the per-item flood that aggregation exists to prevent, so
	 * {@code ProcessorEndpoint} discards it. Retained so an older worker's frames are
	 * recognised and dropped rather than answered with an error once per item.</p>
	 */
	PIPELINE_EVENT,

	/** Pipeline run completed notification from processor */
	PIPELINE_RUN_COMPLETED,

	/** A batch of media items discovered by a source node (Variant C) */
	SOURCE_ITEMS,

	/** The source node finished enumerating (Variant C) */
	SOURCE_COMPLETE,

	/** Outcome of a single node task (Variant C) */
	NODE_TASK_RESULT,

	/**
	 * Hands a dispatched task back unexecuted, so Loom can place it elsewhere without
	 * waiting out its lease. Sent by a draining worker for work it will not finish.
	 */
	TASK_RETURNED,

	// --- Messages FROM loom TO processor ---

	/** Registration acknowledgement from loom */
	REGISTERED,

	/**
	 * Per-node outcome of a {@link #NODE_REGISTRATION}: what was adopted, and why anything else was
	 * not. Never a single verdict — one malformed custom node must not cost a worker its other specs.
	 */
	NODE_REGISTRATION_ACK,

	/** Heartbeat pong from loom */
	HEARTBEAT_ACK,

	/** Instructs a processor to run a source node and stream back what it finds (Variant C) */
	SOURCE_TASK,

	/** Acknowledges a SOURCE_ITEMS batch, releasing the processor to send the next (Variant C) */
	SOURCE_ITEMS_ACK,

	/** A single unit of work: apply one node to one media item (Variant C) */
	NODE_TASK,

	/**
	 * Several connected nodes applied to one media item, run together on one worker
	 * so intermediate results never cross the network (affinity groups, Phase 3).
	 */
	SEGMENT_TASK,

	/** Per-node outcomes of a SEGMENT_TASK; never a single verdict for the segment */
	SEGMENT_TASK_RESULT,

	/**
	 * Several NODE_TASK_RESULTs for one run, sent together. Purely a transport
	 * saving - each entry is assimilated exactly as if it had arrived alone.
	 */
	NODE_TASK_RESULT_BATCH,

	/** Error message */
	ERROR
}
