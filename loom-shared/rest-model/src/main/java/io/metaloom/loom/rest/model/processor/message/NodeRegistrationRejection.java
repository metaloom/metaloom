package io.metaloom.loom.rest.model.processor.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Why one announced node was not adopted.
 *
 * <p>
 * Rejections are reported per node rather than per frame, and they are reported at all rather than
 * logged server-side, because the alternative is an author editing a fork's ports, seeing no effect,
 * and losing an afternoon to it.
 * </p>
 */
public class NodeRegistrationRejection implements RestModel {

	/**
	 * Why an announcement was refused.
	 *
	 * <p>
	 * A closed vocabulary so a worker can react programmatically — {@link #BUILTIN} is routine and
	 * worth an INFO line, {@link #CONFLICT} means two workers disagree about the same contract and
	 * needs an operator, and the {@code INVALID_*} reasons are bugs in the announcing node.
	 * </p>
	 */
	public enum Reason {

		/** A built-in node of the same id exists. Loom's compiled contract wins; the copy is ignored. */
		BUILTIN,

		/** Another worker already announced this id and version with a different body. */
		CONFLICT,

		/** A port id does not match the required shape, or repeats within one side. */
		INVALID_PORT_ID,

		/** A content type is not {@code family/subtype} with both segments non-empty. */
		INVALID_CONTENT_TYPE,

		/** The node id is blank or not lowercase-kebab. */
		INVALID_NODE_ID,

		/** The frame or one of its nodes exceeds a size cap. */
		TOO_LARGE,

		/** Two entries in one frame claim the same node id. */
		DUPLICATE_NODE_ID,

		/** The frame's {@code cortexId} is not the id this socket registered with. */
		ID_MISMATCH,

		/** A port names a group that is not declared on its own side. */
		UNKNOWN_GROUP,

		/** Loom is configured to serve built-in contracts only. */
		ANNOUNCEMENTS_DISABLED
	}

	@JsonProperty(required = true)
	@JsonPropertyDescription("The node type id that was rejected")
	private String nodeId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Machine-readable rejection reason")
	private Reason reason;

	@JsonPropertyDescription("Human-readable explanation, naming the offending field where there is one")
	private String message;

	public NodeRegistrationRejection() {
	}

	public NodeRegistrationRejection(String nodeId, Reason reason, String message) {
		this.nodeId = nodeId;
		this.reason = reason;
		this.message = message;
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeRegistrationRejection setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public Reason getReason() {
		return reason;
	}

	public NodeRegistrationRejection setReason(Reason reason) {
		this.reason = reason;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public NodeRegistrationRejection setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public String toString() {
		return nodeId + ": " + reason + (message == null ? "" : " (" + message + ")");
	}
}
