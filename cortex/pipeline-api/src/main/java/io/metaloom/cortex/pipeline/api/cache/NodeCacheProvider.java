package io.metaloom.cortex.pipeline.api.cache;

import java.util.Optional;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Cache abstraction for node results. Enables pluggable caching strategies:
 * in-memory (Caffeine), filesystem-backed, or external (Redis, etc.).
 */
public interface NodeCacheProvider {

	/**
	 * Look up a cached result for the given node and media combination.
	 *
	 * @param nodeId the pipeline node id
	 * @param media  the media item
	 * @return cached result if present
	 */
	Optional<NodeResult> get(String nodeId, LoomMedia media);

	/**
	 * Store a result in the cache.
	 *
	 * @param nodeId the pipeline node id
	 * @param media  the media item
	 * @param result the result to cache
	 */
	void put(String nodeId, LoomMedia media, NodeResult result);

	/**
	 * Invalidate any cached result for the given node and media.
	 *
	 * @param nodeId the pipeline node id
	 * @param media  the media item
	 */
	void invalidate(String nodeId, LoomMedia media);

	/**
	 * Clear all cached entries.
	 */
	void clear();
}
