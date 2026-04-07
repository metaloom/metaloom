package io.metaloom.cortex.api.node;

import java.io.IOException;
import java.util.Map;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * A Cortex node which is capable of processing {@link LoomMedia}.
 *
 * @param <I> the input type this node accepts
 * @param <O> the output type this node produces
 * @param <T> the options type for this node
 */
public interface FilesystemNode<I, O, T extends CortexNodeOptions> extends CortexNode<I, O, T> {

	/**
	 * Process the input and produce a typed result.
	 *
	 * @param ctx the processing context wrapping the input
	 * @return the result containing the computed output
	 * @throws IOException if processing fails due to I/O
	 */
	NodeResult<O> process(NodeContext<I> ctx) throws IOException;

	default NodeResult<O> process(LoomMedia media) throws IOException {
		@SuppressWarnings("unchecked")
		NodeContext<I> ctx = (NodeContext<I>) NodeContext.create(media);
		return process(ctx);
	}

	/**
	 * Process with upstream results available. Used by the pipeline adapter to
	 * pass dependency outputs to the node.
	 *
	 * @param media            the media item
	 * @param upstreamOutputs  outputs from upstream nodes, keyed by node id
	 * @return the result
	 */
	default NodeResult<O> process(LoomMedia media, Map<String, Map<String, Object>> upstreamOutputs) throws IOException {
		@SuppressWarnings("unchecked")
		NodeContext<I> ctx = (NodeContext<I>) NodeContext.create(media, upstreamOutputs);
		return process(ctx);
	}

	void print(NodeContext<?> ctx, String result, String msg);

	void error(LoomMedia media, String msg);

	default void flush() {
		// NOOP
	}

	void set(long current, long total);
}
