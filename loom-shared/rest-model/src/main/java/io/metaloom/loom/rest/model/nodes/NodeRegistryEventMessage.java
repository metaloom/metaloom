package io.metaloom.loom.rest.model.nodes;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * A registry frame on the shared UI events socket.
 *
 * <p>
 * That socket already multiplexes two channels — pipeline frames carry no {@code channel} field,
 * processor frames carry {@code "PROCESSOR"} — so this adds a third rather than a second socket with
 * its own reconnect and backoff logic to get wrong.
 * </p>
 *
 * <p>
 * The two event types are deliberately separate. Presence changes on every worker connect,
 * disconnect, restart and scale event; the descriptor set changes when someone deploys. Collapsing
 * them into one "something changed" frame would make every rolling restart trigger a ~115 KB re-fetch
 * in every open editor tab to discover that one boolean flipped.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeRegistryEventMessage implements RestModel {

	public enum Type {

		/**
		 * The descriptor set or a contract's content changed — a node id appeared or went away, or a
		 * body was replaced. The client re-fetches the full list.
		 */
		NODE_DESCRIPTORS_CHANGED,

		/**
		 * Presence only. Carries the changed entries inline so the client patches in place and fetches
		 * nothing.
		 */
		NODE_AVAILABILITY_CHANGED
	}

	/** Discriminator for the shared UI socket. Pipeline frames have none; processor frames say PROCESSOR. */
	@JsonProperty(required = true)
	private final String channel = "NODE_REGISTRY";

	@JsonProperty(required = true)
	private Type type;

	@JsonPropertyDescription("For NODE_AVAILABILITY_CHANGED: the entries that changed, keyed by node id")
	private Map<String, NodeAvailability> availability;

	public NodeRegistryEventMessage() {
	}

	public NodeRegistryEventMessage(Type type) {
		this.type = type;
	}

	public String getChannel() {
		return channel;
	}

	public Type getType() {
		return type;
	}

	public NodeRegistryEventMessage setType(Type type) {
		this.type = type;
		return this;
	}

	public Map<String, NodeAvailability> getAvailability() {
		return availability;
	}

	public NodeRegistryEventMessage setAvailability(Map<String, NodeAvailability> availability) {
		this.availability = availability;
		return this;
	}
}
