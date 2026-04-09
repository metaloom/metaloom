package io.metaloom.cortex.api.node;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * Marker interface for nodes that act as a source of media assets in the pipeline.
 * A pipeline must have exactly one source node. Source nodes are the entry point
 * that yield {@link io.metaloom.cortex.api.media.LoomMedia} items for downstream processing.
 *
 * @param <I> the input type this node accepts
 * @param <O> the output type this node produces
 * @param <T> the options type for this node
 */
public interface SourceNode<I, O, T extends CortexNodeOptions> extends CortexNode<I, O, T> {
}
