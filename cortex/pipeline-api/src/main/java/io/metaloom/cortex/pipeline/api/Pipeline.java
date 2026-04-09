package io.metaloom.cortex.pipeline.api;

import java.util.List;

import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * A processing pipeline that defines an ordered graph of nodes to execute on media assets.
 * Every pipeline has exactly one source node that yields media assets for processing.
 */
public interface Pipeline {

	/**
	 * Unique name of this pipeline.
	 */
	String name();

	/**
	 * Human-readable description.
	 */
	String description();

	/**
	 * Priority for pipeline selection when multiple pipelines match.
	 * Higher values take precedence.
	 */
	int priority();

	/**
	 * Whether this pipeline is enabled.
	 */
	boolean isEnabled();

	/**
	 * Whether this pipeline is in dry-run mode (nodes log but do not mutate state).
	 */
	boolean isDryRun();

	/**
	 * The single source node of this pipeline.
	 */
	PipelineNode sourceNode();

	/**
	 * All nodes in this pipeline in topological order (respecting dependencies).
	 */
	List<PipelineNode> nodes();

	/**
	 * Get a node by its id.
	 *
	 * @param nodeId the node identifier
	 * @return the node, or null if not found
	 */
	PipelineNode node(String nodeId);
}
