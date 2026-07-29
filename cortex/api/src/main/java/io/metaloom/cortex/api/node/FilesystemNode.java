package io.metaloom.cortex.api.node;

import java.io.IOException;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * A Cortex node which is capable of processing {@link LoomMedia}.
 * Filesystem nodes are source nodes — they yield media assets for downstream processing.
 *
 * @param <I> the input type this node accepts
 * @param <T> the options type for this node
 */
public interface FilesystemNode<I, T extends CortexNodeOptions> extends SourceNode<I, T> {

	/**
	 * Process the input and produce a typed result.
	 *
	 * @param ctx the processing context wrapping the input
	 * @return the result containing the computed output
	 * @throws IOException if processing fails due to I/O
	 */
	NodeResult process(NodeContext<I> ctx) throws IOException;

	default NodeResult process(LoomMedia media) throws IOException {
		@SuppressWarnings("unchecked")
		NodeContext<I> ctx = (NodeContext<I>) NodeContext.create(media);
		return process(ctx);
	}

	/**
	 * Process with the pipeline's port-keyed view of this node's inputs.
	 *
	 * <p>The view is keyed by <em>this</em> node's input port ids - the engine has
	 * already resolved which upstream node and port fills each one - so the node
	 * never learns what the pipeline author named its neighbours.</p>
	 *
	 * @param media  the media item
	 * @param inputs what this node's input ports carry, plus the demanded outputs and origin
	 * @return the result
	 */
	default NodeResult process(LoomMedia media, NodeInputs inputs) throws IOException {
		@SuppressWarnings("unchecked")
		NodeContext<I> ctx = (NodeContext<I>) NodeContext.create(media, inputs);
		return process(ctx);
	}

	void print(NodeContext<?> ctx, String result, String msg);

	void error(LoomMedia media, String msg);

	default void flush() {
		// NOOP
	}

	void set(long current, long total);
}
