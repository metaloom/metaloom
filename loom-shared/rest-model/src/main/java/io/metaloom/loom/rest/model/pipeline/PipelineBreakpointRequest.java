package io.metaloom.loom.rest.model.pipeline;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * The set of nodes a run should halt at.
 *
 * <p>
 * Deliberately a whole-set replacement rather than an add/remove pair. The editor holds the armed
 * set in front of the operator and sends what it should become; a delta protocol would let the two
 * drift apart, and "which breakpoints are actually armed" is precisely the question a debugger must
 * never be vague about.
 * </p>
 */
public class PipelineBreakpointRequest implements RestRequestModel {

	@JsonPropertyDescription("Node ids to halt at. Replaces the armed set; an empty list disarms everything.")
	private List<String> nodeIds;

	public PipelineBreakpointRequest() {
	}

	public List<String> getNodeIds() {
		return nodeIds;
	}

	public PipelineBreakpointRequest setNodeIds(List<String> nodeIds) {
		this.nodeIds = nodeIds;
		return this;
	}
}
