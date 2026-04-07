package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Adapter that wraps an existing {@link FilesystemNode} as a {@link io.metaloom.cortex.pipeline.api.node.PipelineNode}.
 * This allows the pipeline system to reuse all existing Cortex nodes.
 */
public class CortexNodeAdapter extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(CortexNodeAdapter.class);

	private final FilesystemNode<?, ?, ?> wrappedNode;

	public CortexNodeAdapter(FilesystemNode<?, ?, ?> wrappedNode, NodeMode mode, boolean blocking,
			Set<String> dependencies, int concurrency) {
		super(wrappedNode.name(), wrappedNode.name(), mode, blocking, dependencies, concurrency);
		this.wrappedNode = wrappedNode;
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		long start = System.currentTimeMillis();
		try {
			io.metaloom.cortex.api.node.NodeResult<?> result = wrappedNode.process(media);
			long elapsed = System.currentTimeMillis() - start;
			if (result == null) {
				return NodeResult.failed(id(), elapsed, "Node returned null result");
			}
			switch (result.getState()) {
				case SUCCESS:
					return NodeResult.success(id(), elapsed);
				case SKIPPED:
					return NodeResult.skipped(id(), "Node skipped");
				case FAILED:
				default:
					return NodeResult.failed(id(), elapsed, "Node failed");
			}
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

	public FilesystemNode<?, ?, ?> getWrappedNode() {
		return wrappedNode;
	}
}
