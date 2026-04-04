package io.metaloom.cortex.pipeline.common.cache;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;

/**
 * In-memory cache backed by Caffeine. Suitable for single-instance deployments
 * and development. Configurable maximum size and TTL.
 */
public class HeapNodeCache implements NodeCacheProvider {

	private final Cache<String, NodeResult> cache;

	public HeapNodeCache(long maxSize, long ttlMinutes) {
		this.cache = Caffeine.newBuilder()
				.maximumSize(maxSize)
				.expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
				.build();
	}

	public HeapNodeCache() {
		this(10_000, 60);
	}

	@Override
	public Optional<NodeResult> get(String nodeId, LoomMedia media) {
		return Optional.ofNullable(cache.getIfPresent(cacheKey(nodeId, media)));
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		cache.put(cacheKey(nodeId, media), result);
	}

	@Override
	public void invalidate(String nodeId, LoomMedia media) {
		cache.invalidate(cacheKey(nodeId, media));
	}

	@Override
	public void clear() {
		cache.invalidateAll();
	}

	private String cacheKey(String nodeId, LoomMedia media) {
		String hash = media.getSHA512() != null ? media.getSHA512().toString() : media.absolutePath();
		return nodeId + ":" + hash;
	}
}
