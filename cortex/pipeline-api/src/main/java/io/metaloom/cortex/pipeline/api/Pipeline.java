package io.metaloom.cortex.pipeline.api;

import java.util.List;
import java.util.stream.Stream;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.filter.PipelineFilter;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * A processing pipeline that defines an ordered graph of nodes to execute on media assets.
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
	 * The filter that determines which media items this pipeline applies to.
	 */
	PipelineFilter filter();

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

	/**
	 * Test whether this pipeline should process the given media.
	 *
	 * @param media the media item
	 * @return true if the pipeline's filter matches
	 */
	default boolean matches(LoomMedia media) {
		PipelineFilter f = filter();
		return f == null || f.matches(media);
	}
}
