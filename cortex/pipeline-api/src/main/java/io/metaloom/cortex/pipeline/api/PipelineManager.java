package io.metaloom.cortex.pipeline.api;

import java.util.List;
import java.util.Optional;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Manages the set of registered pipelines and resolves which pipeline applies to a given media item.
 */
public interface PipelineManager {

	/**
	 * Register a pipeline.
	 *
	 * @param pipeline the pipeline to register
	 */
	void register(Pipeline pipeline);

	/**
	 * Unregister a pipeline by name.
	 *
	 * @param name the pipeline name
	 * @return true if the pipeline was found and removed
	 */
	boolean unregister(String name);

	/**
	 * Get all registered pipelines, ordered by priority descending.
	 */
	List<Pipeline> pipelines();

	/**
	 * Get a pipeline by name.
	 */
	Optional<Pipeline> pipeline(String name);

	/**
	 * Resolve the best-matching pipeline for a given media item.
	 * Returns the highest-priority enabled pipeline whose filter matches.
	 *
	 * @param media the media item
	 * @return the matching pipeline, or empty if no pipeline matches
	 */
	Optional<Pipeline> resolve(LoomMedia media);
}
