package io.metaloom.cortex.pipeline.core.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.SourceNode;
import io.metaloom.cortex.pipeline.api.NodeMode;

/**
 * Adapter that wraps an existing {@link FilesystemNode} as a {@link io.metaloom.cortex.pipeline.api.node.PipelineNode}.
 * This allows the pipeline system to reuse all existing Cortex nodes.
 *
 * <p>The adapter drives the legacy {@code process(LoomMedia, …)} lifecycle unchanged;
 * only the upstream view it hands over changed shape, from a map keyed by upstream node
 * id to the node's own {@link NodeInputs} ports. Since node and pipeline results are the
 * same {@link NodeResult} type, it simply stamps the wrapped node's result with this
 * adapter's pipeline id and elapsed time via {@link NodeResult#withNode}.</p>
 */
public class CortexNodeAdapter extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(CortexNodeAdapter.class);

	private final FilesystemNode<?, ?> wrappedNode;

	public CortexNodeAdapter(FilesystemNode<?, ?> wrappedNode, NodeMode mode, boolean blocking, int concurrency) {
		this(wrappedNode.name(), wrappedNode, mode, blocking, concurrency, 0);
	}

	public CortexNodeAdapter(FilesystemNode<?, ?> wrappedNode, NodeMode mode, boolean blocking, int concurrency, long timeoutMs) {
		this(wrappedNode.name(), wrappedNode, mode, blocking, concurrency, timeoutMs);
	}

	/**
	 * Alternate constructor which allows overriding the pipeline node id (defaults to {@code wrappedNode.name()}).
	 * Useful when a downstream node expects a specific upstream node id that does not match the wrapped node's own name.
	 */
	public CortexNodeAdapter(String id, FilesystemNode<?, ?> wrappedNode, NodeMode mode, boolean blocking, int concurrency) {
		this(id, wrappedNode, mode, blocking, concurrency, 0);
	}

	public CortexNodeAdapter(String id, FilesystemNode<?, ?> wrappedNode, NodeMode mode, boolean blocking, int concurrency, long timeoutMs) {
		super(id, wrappedNode.name(), mode, blocking, concurrency, false, timeoutMs);
		this.wrappedNode = wrappedNode;
	}

	@Override
	public boolean isSource() {
		return wrappedNode instanceof SourceNode;
	}

	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		long start = System.currentTimeMillis();
		try {
			NodeResult result = wrappedNode.process(media, inputs == null ? NodeInputs.empty() : inputs);
			long elapsed = System.currentTimeMillis() - start;
			if (result == null) {
				return NodeResult.failed(id(), elapsed, "Node returned null result");
			}
			// Node and pipeline results are the same type now; stamp this adapter's
			// pipeline id and measured elapsed onto the node's result, preserving its
			// state, message (skip reason / failure cause) and outputs.
			return result.withNode(id(), elapsed);
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			log.error("Error executing cortex node {}: {}", id(), e.getMessage(), e);
			return NodeResult.failed(id(), elapsed, e.getMessage());
		}
	}

	@Override
	public void initialize() {
		wrappedNode.initialize();
	}

	public FilesystemNode<?, ?> getWrappedNode() {
		return wrappedNode;
	}
}
