package io.metaloom.loom.rest.model.pipeline;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What a re-execution request started.
 *
 * <p>
 * The generation is the useful part: it is the value the new task row will carry, so the caller can
 * name the attempt it just asked for and show it beside the one before it. The result itself arrives
 * the way every other result does — the node is dispatched, comes back, and is held at the same
 * breakpoint again.
 * </p>
 */
public class PipelineNodeReExecuteResponse implements RestResponseModel<PipelineNodeReExecuteResponse> {

	@JsonPropertyDescription("Which attempt this re-execution is recorded as; counts from 1, the original run being 0.")
	private int generation;

	@JsonPropertyDescription("The node id that is running again.")
	private String nodeId;

	@JsonPropertyDescription("The settings the node is now running with, the pipeline's own merged with the run-scoped override.")
	private Map<String, Object> options;

	public PipelineNodeReExecuteResponse() {
	}

	public int getGeneration() {
		return generation;
	}

	public PipelineNodeReExecuteResponse setGeneration(int generation) {
		this.generation = generation;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public PipelineNodeReExecuteResponse setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public PipelineNodeReExecuteResponse setOptions(Map<String, Object> options) {
		this.options = options;
		return this;
	}

	@Override
	public PipelineNodeReExecuteResponse self() {
		return this;
	}
}
