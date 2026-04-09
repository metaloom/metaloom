package io.metaloom.cortex.api.node;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * A processing node in the Cortex pipeline graph. Each node accepts a typed input {@code I},
 * produces a typed output {@code O}, and is configured via options {@code T}.
 *
 * <p>Nodes are connected manually in a directed graph — execution order is determined
 * by the graph topology, not by an order enum.
 *
 * <p>Nodes are <em>not</em> singletons; the same node type may be instantiated multiple
 * times with different options.
 *
 * @param <I> the input type this node accepts
 * @param <T> the options type for this node
 */
public interface CortexNode<I, T extends CortexNodeOptions> {

	/**
	 * Name of the node.
	 */
	String name();

	/**
	 * Check whether the node is configured for dryrun.
	 */
	boolean isDryrun();

	/**
	 * Return the options for the node.
	 */
	T options();

	CortexOptions cortexOption();

	/**
	 * Initialize the node.
	 */
	void initialize();
}
