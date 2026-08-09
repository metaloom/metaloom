package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * One thing wrong with a pipeline definition.
 *
 * <p>
 * The {@code code} is the stable part and the only thing a client should branch on; the {@code message} is written for a human and will be reworded.
 * {@code nodeId} and {@code edgeId} are what let the editor put the error on the canvas rather than in a toast — both are null for a problem with the
 * definition as a whole, such as an empty graph.
 * </p>
 */
public class PipelineValidationError implements RestModel {

	@JsonPropertyDescription("Stable machine-readable code for the rule that was broken, e.g. CYCLE or NODE_ID_DUPLICATE.")
	private String code;

	@JsonPropertyDescription("Human-readable explanation of the problem.")
	private String message;

	@JsonPropertyDescription("The node the error belongs to, or null when it is not about one node.")
	private String nodeId;

	@JsonPropertyDescription("The edge the error belongs to, rendered as \"source->target\"; null when the error is not about an edge.")
	private String edgeId;

	public PipelineValidationError() {
	}

	public PipelineValidationError(String code, String message, String nodeId, String edgeId) {
		this.code = code;
		this.message = message;
		this.nodeId = nodeId;
		this.edgeId = edgeId;
	}

	public String getCode() {
		return code;
	}

	public PipelineValidationError setCode(String code) {
		this.code = code;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public PipelineValidationError setMessage(String message) {
		this.message = message;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public PipelineValidationError setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getEdgeId() {
		return edgeId;
	}

	public PipelineValidationError setEdgeId(String edgeId) {
		this.edgeId = edgeId;
		return this;
	}

	@Override
	public String toString() {
		return code + ": " + message;
	}
}
