package io.metaloom.loom.agent.chat.event;

import io.vertx.core.json.JsonObject;

/**
 * A single event of an agent run (see CHAT.md §4.2 for the payload shapes).
 */
public record AgentEvent(AgentEventType type, JsonObject data) {

	public static AgentEvent of(AgentEventType type, JsonObject data) {
		return new AgentEvent(type, data);
	}

	public static AgentEvent of(AgentEventType type) {
		return new AgentEvent(type, new JsonObject());
	}

}
