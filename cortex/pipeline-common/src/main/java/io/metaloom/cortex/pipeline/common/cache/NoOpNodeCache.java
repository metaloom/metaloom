package io.metaloom.cortex.pipeline.common.cache;

import java.util.Optional;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;

/**
 * No-op cache that never stores or returns results. Used as default when caching is disabled.
 */
public class NoOpNodeCache implements NodeCacheProvider {

	public static final NoOpNodeCache INSTANCE = new NoOpNodeCache();

	@Override
	public Optional<NodeResult> get(String nodeId, LoomMedia media) {
		return Optional.empty();
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		// no-op
	}

	@Override
	public void invalidate(String nodeId, LoomMedia media) {
		// no-op
	}

	@Override
	public void clear() {
		// no-op
	}
}
