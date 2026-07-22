package io.metaloom.loom.ai.event;

/**
 * Event vocabulary of the agentic loop. Events are streamed to the client via SSE using the lowercase form (e.g. {@code reasoning_delta}).
 */
public enum AgentEventType {

	AGENT_START,

	TURN_START,

	/**
	 * A chunk of model reasoning ("thinking"). Kept distinct from {@link #TEXT_DELTA} so clients can hide reasoning by default.
	 */
	REASONING_DELTA,

	TEXT_DELTA,

	TOOL_START,

	TOOL_END,

	TURN_END,

	/**
	 * The final, authoritative assistant message (as persisted). Clients replace their accumulated deltas with it.
	 */
	MESSAGE_END,

	TITLE,

	ERROR,

	AGENT_END;

	private final String wireName = name().toLowerCase();

	/**
	 * The name used on the wire (SSE event field).
	 */
	public String wireName() {
		return wireName;
	}

}
