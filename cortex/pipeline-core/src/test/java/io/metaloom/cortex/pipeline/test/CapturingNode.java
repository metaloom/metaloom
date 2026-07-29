package io.metaloom.cortex.pipeline.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Test-only downstream node that captures whatever arrives on one declared input port.
 *
 * <p>It used to name an upstream node id and an output key. That is precisely the
 * addressing the port model removed: what it captures is now decided by the port it
 * declares, and the harness fills that port from whatever upstream emitted it.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * CapturingNode capture = new CapturingNode("consumer", SHA512Node.OUT_HASH);
 * PipelineResult result = execute(media, upstreamAdapter, capture);
 * assertThat(capture.capturedValues()).containsExactly(expectedSha512);
 * </pre>
 */
public class CapturingNode extends AbstractPipelineNode {

	/** What this node re-emits, so a further downstream assertion can read it. */
	public static final OutputPort<String> OUT_CAPTURED =
		OutputPort.one("captured", ContentTypeRegistry.SCALAR_STRING, String.class);

	private final InputPort<?> port;
	private final List<Object> captured = new CopyOnWriteArrayList<>();

	public CapturingNode(String id, InputPort<?> port) {
		super(id, id, NodeMode.SEQUENTIAL, true, 1);
		this.port = port;
	}

	/** Capture whatever an upstream output port carries, by mirroring its id and type. */
	public CapturingNode(String id, OutputPort<?> upstream) {
		this(id, new InputPort<>(upstream.id(), upstream.contentType(), upstream.cardinality(), upstream.valueType()));
	}

	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media, inputs == null ? NodeInputs.empty() : inputs);
		Object value = ctx.input(port);
		captured.add(value);
		return NodeResult.success(id(), 0,
			Map.of(OUT_CAPTURED.id(), PortOutput.one(OUT_CAPTURED, value != null ? String.valueOf(value) : "")));
	}

	/**
	 * Values captured (in order) from the declared input port. A {@code null} entry
	 * means nothing was wired into it for that run.
	 */
	public List<Object> capturedValues() {
		return captured;
	}
}
