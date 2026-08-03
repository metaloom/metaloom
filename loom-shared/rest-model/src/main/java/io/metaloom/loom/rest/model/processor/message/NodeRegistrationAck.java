package io.metaloom.loom.rest.model.processor.message;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Loom's per-node answer to a {@link NodeRegistration}.
 *
 * <p>
 * One malformed custom node must not cost a worker its other 34 specs, so the outcome is a partition
 * rather than a verdict: everything valid is adopted and everything else is named, with a reason a
 * worker can log without a human decoding it.
 * </p>
 */
public class NodeRegistrationAck implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The worker this acknowledgement is for")
	private String cortexId;

	@JsonPropertyDescription("Node type ids whose contract Loom now serves on this worker's behalf")
	private List<String> accepted = new ArrayList<>();

	@JsonPropertyDescription("Node type ids that were not adopted, each with a reason")
	private List<NodeRegistrationRejection> rejected = new ArrayList<>();

	public NodeRegistrationAck() {
	}

	public NodeRegistrationAck(String cortexId) {
		this.cortexId = cortexId;
	}

	public String getCortexId() {
		return cortexId;
	}

	public NodeRegistrationAck setCortexId(String cortexId) {
		this.cortexId = cortexId;
		return this;
	}

	public List<String> getAccepted() {
		return accepted;
	}

	public NodeRegistrationAck setAccepted(List<String> accepted) {
		this.accepted = accepted;
		return this;
	}

	public List<NodeRegistrationRejection> getRejected() {
		return rejected;
	}

	public NodeRegistrationAck setRejected(List<NodeRegistrationRejection> rejected) {
		this.rejected = rejected;
		return this;
	}

	public NodeRegistrationAck accept(String nodeId) {
		accepted.add(nodeId);
		return this;
	}

	public NodeRegistrationAck reject(String nodeId, NodeRegistrationRejection.Reason reason, String message) {
		rejected.add(new NodeRegistrationRejection(nodeId, reason, message));
		return this;
	}
}
