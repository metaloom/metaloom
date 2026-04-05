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

	// --- Messages FROM loom TO processor ---

	/** Registration acknowledgement from loom */
	REGISTERED,

	/** Heartbeat pong from loom */
	HEARTBEAT_ACK,

	/** Work order dispatched to processor */
	WORK_ORDER,

	/** Error message */
	ERROR
}
