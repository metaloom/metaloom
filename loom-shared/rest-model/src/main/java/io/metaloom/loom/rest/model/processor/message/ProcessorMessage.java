package io.metaloom.loom.rest.model.processor.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

/**
 * Envelope for all messages exchanged over the processor WebSocket connection.
 * The {@link #type} field determines how the {@link #body} should be interpreted.
 */
public class ProcessorMessage implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The type of message")
	private ProcessorMessageType type;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The message payload, structure depends on type")
	private JsonObject body;

	public ProcessorMessage() {
	}

	public ProcessorMessage(ProcessorMessageType type) {
		this.type = type;
	}

	public ProcessorMessage(ProcessorMessageType type, JsonObject body) {
		this.type = type;
		this.body = body;
	}

	public ProcessorMessageType getType() {
		return type;
	}

	public ProcessorMessage setType(ProcessorMessageType type) {
		this.type = type;
		return this;
	}

	public JsonObject getBody() {
		return body;
	}

	public ProcessorMessage setBody(JsonObject body) {
		this.body = body;
		return this;
	}
}
