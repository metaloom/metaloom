package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;

/**
 * Test-only upstream node that emits a fixed set of port outputs. Filter nodes read
 * their inputs from declared ports rather than from {@link LoomMedia} (see
 * {@link AbstractFilterNodeTest} for the split), so most filter tests need a node
 * that stands in for a quality/hash/tika node without running it.
 */
class FixedOutputNode extends AbstractPipelineNode {

	private final Map<String, PortOutput> outputs;

	FixedOutputNode(String id, Map<String, PortOutput> outputs) {
		super(id, id, NodeMode.SEQUENTIAL, true, 1);
		this.outputs = outputs;
	}

	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		return NodeResult.success(id(), 0, outputs);
	}
}
