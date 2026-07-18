package io.metaloom.loom.rest.model.processor.message;

/**
 * Types of messages exchanged over the processor WebSocket connection.
 */
public enum ProcessorMessageType {

	// --- Messages FROM processor TO loom ---

	/** Initial registration of a processor node */
	REGISTER,

	/** Heartbeat ping from processor */
	HEARTBEAT,

	/** System status update from processor */
	STATUS_UPDATE,

	/** State change notification from processor */
	STATE_CHANGE,

	/** Work order result from processor */
	WORK_ORDER_RESULT,

	/** Pipeline tracking event from processor (forwarded to UI clients) */
	PIPELINE_EVENT,

	/** Pipeline run completed notification from processor */
	PIPELINE_RUN_COMPLETED,

	/** A batch of media items discovered by a source node (Variant C) */
	SOURCE_ITEMS,

	/** The source node finished enumerating (Variant C) */
	SOURCE_COMPLETE,

	/** Outcome of a single node task (Variant C) */
	NODE_TASK_RESULT,

	// --- Messages FROM loom TO processor ---

	/** Registration acknowledgement from loom */
	REGISTERED,

	/** Heartbeat pong from loom */
	HEARTBEAT_ACK,

	/** Work order dispatched to processor */
	WORK_ORDER,

	/** Instructs a processor to run a source node and stream back what it finds (Variant C) */
	SOURCE_TASK,

	/** Acknowledges a SOURCE_ITEMS batch, releasing the processor to send the next (Variant C) */
	SOURCE_ITEMS_ACK,

	/** A single unit of work: apply one node to one media item (Variant C) */
	NODE_TASK,

	/** Error message */
	ERROR
}
