package io.metaloom.loom.rest.model.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What a run is halting at, and what it is currently holding.
 *
 * <p>
 * The two lists answer different questions and neither implies the other. {@code nodeIds} is what
 * the operator armed; {@code held} is what has actually stopped so far. A breakpoint on a node no
 * item has reached yet is armed and holding nothing, and a run can still be holding an execution of
 * a node that was disarmed a moment ago on a different browser tab.
 * </p>
 */
public class PipelineBreakpointResponse implements RestResponseModel<PipelineBreakpointResponse> {

	@JsonPropertyDescription("Node ids the run is armed to halt at.")
	private List<String> nodeIds = new ArrayList<>();

	@JsonPropertyDescription("Executions currently withheld from their downstream nodes.")
	private List<PipelineHeldExecution> held = new ArrayList<>();

	public PipelineBreakpointResponse() {
	}

	public List<String> getNodeIds() {
		return nodeIds;
	}

	public PipelineBreakpointResponse setNodeIds(List<String> nodeIds) {
		this.nodeIds = nodeIds;
		return this;
	}

	public List<PipelineHeldExecution> getHeld() {
		return held;
	}

	public PipelineBreakpointResponse setHeld(List<PipelineHeldExecution> held) {
		this.held = held;
		return this;
	}

	@Override
	public PipelineBreakpointResponse self() {
		return this;
	}
}
