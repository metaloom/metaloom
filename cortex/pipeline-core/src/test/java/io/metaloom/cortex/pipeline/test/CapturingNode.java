package io.metaloom.cortex.pipeline.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;

/**
 * Test-only downstream node that captures a single output value produced by
 * an upstream node. Replaces the anonymous {@code AbstractPipelineNode}
 * boilerplate previously duplicated in every {@code *NodePipelineTest#testOutputChaining()}.
 *
 * <h3>Usage</h3>
 * <pre>
 * CapturingNode capture = new CapturingNode("consumer", "sha512", "sha512");
 * PipelineResult result = execute(media, upstreamAdapter, capture);
 * assertThat(capture.capturedValues()).containsExactly(expectedSha512);
 * </pre>
 */
public class CapturingNode extends AbstractPipelineNode {

	private final String upstreamNodeId;
	private final String outputKey;
	private final List<Object> captured = new CopyOnWriteArrayList<>();

	public CapturingNode(String id, String upstreamNodeId, String outputKey) {
		super(id, id, NodeMode.SEQUENTIAL, true, 1);
		this.upstreamNodeId = upstreamNodeId;
		this.outputKey = outputKey;
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		NodeResult upstream = upstreamResults.get(upstreamNodeId);
		Object value = upstream != null ? upstream.getOutput(outputKey) : null;
		captured.add(value);
		return NodeResult.success(id(), 0, Map.of("captured", value != null ? value : ""));
	}

	/**
	 * Values captured (in order) from the upstream node's {@code outputKey} output.
	 * A {@code null} entry means the upstream result had no value for that key.
	 */
	public List<Object> capturedValues() {
		return captured;
	}

	/**
	 * Typed convenience getter for the first captured value.
	 */
	@SuppressWarnings("unchecked")
	public <T> T firstCaptured() {
		return captured.isEmpty() ? null : (T) captured.get(0);
	}
}
