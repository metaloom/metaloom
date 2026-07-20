package io.metaloom.loom.rest.model.processor.event;

/**
 * Types of processor lifecycle events dispatched over the UI events WebSocket.
 * These events let UI clients (e.g. the Cortex view) reflect live processor
 * state — registrations, state transitions, system metrics and disconnects —
 * without polling.
 */
public enum ProcessorEventType {

	/** A processor node registered / re-registered. Carries a full snapshot. */
	REGISTERED,

	/** A processor changed state (ONLINE/PAUSED/OFFLINE/…). Carries a full snapshot. */
	STATE_CHANGED,

	/** A processor reported fresh system metrics (CPU/GPU/IO/memory). Carries a full snapshot. */
	STATUS_UPDATED,

	/** A processor heartbeat; lightweight — carries only nodeId + lastSeen. */
	HEARTBEAT,

	/** A processor disconnected and was unregistered; carries only nodeId. */
	DISCONNECTED
}
