package io.metaloom.cortex.pipeline.common.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;

/**
 * Layered cache that checks multiple providers in order.
 * On a miss, delegates to the next layer. On a hit, back-fills faster layers.
 * 
 * Example layering: HeapNodeCache -> FsNodeCache -> ExternalNodeCache
 */
public class LayeredNodeCache implements NodeCacheProvider {

	private final NodeCacheProvider[] layers;

	public LayeredNodeCache(NodeCacheProvider... layers) {
		this.layers = layers;
	}

	@Override
	public Optional<NodeResult> get(String nodeId, LoomMedia media) {
		for (int i = 0; i < layers.length; i++) {
			Optional<NodeResult> result = layers[i].get(nodeId, media);
			if (result.isPresent()) {
				// Back-fill faster layers
				for (int j = 0; j < i; j++) {
					layers[j].put(nodeId, media, result.get());
				}
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		for (NodeCacheProvider layer : layers) {
			layer.put(nodeId, media, result);
		}
	}

	@Override
	public void invalidate(String nodeId, LoomMedia media) {
		for (NodeCacheProvider layer : layers) {
			layer.invalidate(nodeId, media);
		}
	}

	@Override
	public void clear() {
		for (NodeCacheProvider layer : layers) {
			layer.clear();
		}
	}
}
