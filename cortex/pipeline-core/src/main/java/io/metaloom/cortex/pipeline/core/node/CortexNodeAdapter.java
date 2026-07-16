package io.metaloom.cortex.pipeline.core.node;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.SourceNode;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Adapter that wraps an existing {@link FilesystemNode} as a {@link io.metaloom.cortex.pipeline.api.node.PipelineNode}.
 * This allows the pipeline system to reuse all existing Cortex nodes.
 *
 * <p>The adapter converts upstream pipeline {@link NodeResult} outputs into a
 * {@code Map<String, Map<String, Object>>} that the wrapped node can access
 * via {@link NodeContext#upstreamOutputs()}. It also extracts the node's
 * outputs from the cortex-level result and forwards them in the pipeline-level result.</p>
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
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		long start = System.currentTimeMillis();
		try {
			// Convert pipeline NodeResult map → upstream outputs map for the cortex node
			Map<String, Map<String, Object>> upstreamOutputs = toUpstreamOutputs(upstreamResults);

			io.metaloom.cortex.api.node.NodeResult result = wrappedNode.process(media, upstreamOutputs);
			long elapsed = System.currentTimeMillis() - start;
			if (result == null) {
				return NodeResult.failed(id(), elapsed, "Node returned null result");
			}
			switch (result.getState()) {
				case SUCCESS:
					// Forward the cortex-level outputs to the pipeline-level result
					Map<String, Object> outputs = result.getOutputs();
					return NodeResult.success(id(), elapsed, outputs);
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

	/**
	 * Convert pipeline-level upstream results into a simple map of maps
	 * that the cortex node can access via NodeContext.upstreamOutputs().
	 */
	private Map<String, Map<String, Object>> toUpstreamOutputs(Map<String, NodeResult> upstreamResults) {
		if (upstreamResults == null || upstreamResults.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<String, Object>> outputs = new HashMap<>();
		for (Map.Entry<String, NodeResult> entry : upstreamResults.entrySet()) {
			NodeResult result = entry.getValue();
			if (result != null && result.getOutput() != null) {
				outputs.put(entry.getKey(), result.getOutput());
			}
		}
		return outputs;
	}

	@Override
	public void initialize() {
		wrappedNode.initialize();
	}

	public FilesystemNode<?, ?> getWrappedNode() {
		return wrappedNode;
	}
}
