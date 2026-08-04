package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * One execution a breakpoint is currently withholding.
 *
 * <p>
 * The three fields together name exactly one execution. {@code elementSeq} matters for a node
 * downstream of a fan-out, which runs once per element: each of those runs is held on its own, and
 * the debug view has to be able to tell them apart to show the right result.
 * </p>
 */
public class PipelineHeldExecution implements RestModel {

	@JsonPropertyDescription("The node that is holding.")
	private String nodeId;

	@JsonPropertyDescription("The run item whose execution is held.")
	private String itemUuid;

	@JsonPropertyDescription("Which element, for a node that runs once per element of an upstream sequence.")
	private int elementSeq;

	public PipelineHeldExecution() {
	}

	public String getNodeId() {
		return nodeId;
	}

	public PipelineHeldExecution setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getItemUuid() {
		return itemUuid;
	}

	public PipelineHeldExecution setItemUuid(String itemUuid) {
		this.itemUuid = itemUuid;
		return this;
	}

	public int getElementSeq() {
		return elementSeq;
	}

	public PipelineHeldExecution setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
	}
}
