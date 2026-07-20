package io.metaloom.loom.rest.model.processor.event;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.processor.ProcessorResponse;

/**
 * Lightweight, JSON-serialisable processor lifecycle event dispatched over the
 * single UI events WebSocket ({@code /api/v1/pipelines/events/ws}).
 *
 * <p>The UI socket is multiplexed: pipeline events keep their original wire
 * format (no {@code channel} field) while processor events always carry
 * {@code "channel": "PROCESSOR"} so clients can route a frame to the right
 * handler without opening a second connection.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessorEventMessage implements RestModel {

	/** Discriminator identifying the multiplexed channel this frame belongs to. */
	public static final String CHANNEL = "PROCESSOR";

	@JsonProperty(required = true)
	@JsonPropertyDescription("Multiplexing channel discriminator; always 'PROCESSOR' for processor events")
	private String channel = CHANNEL;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Processor event type")
	private ProcessorEventType type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Node id of the processor this event refers to (stable UI key)")
	private String nodeId;

	@JsonPropertyDescription("Full processor snapshot (REGISTERED / STATE_CHANGED / STATUS_UPDATED); null for lightweight events")
	private ProcessorResponse processor;

	@JsonPropertyDescription("Timestamp the processor was last seen (carried on HEARTBEAT)")
	private Instant lastSeen;

	public ProcessorEventMessage() {
	}

	public ProcessorEventMessage(ProcessorEventType type, String nodeId) {
		this.type = type;
		this.nodeId = nodeId;
	}

	public String getChannel() {
		return channel;
	}

	public ProcessorEventMessage setChannel(String channel) {
		this.channel = channel;
		return this;
	}

	public ProcessorEventType getType() {
		return type;
	}

	public ProcessorEventMessage setType(ProcessorEventType type) {
		this.type = type;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public ProcessorEventMessage setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public ProcessorResponse getProcessor() {
		return processor;
	}

	public ProcessorEventMessage setProcessor(ProcessorResponse processor) {
		this.processor = processor;
		return this;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public ProcessorEventMessage setLastSeen(Instant lastSeen) {
		this.lastSeen = lastSeen;
		return this;
	}

	@Override
	public String toString() {
		return "ProcessorEventMessage{type=" + type + ", nodeId=" + nodeId + "}";
	}
}
