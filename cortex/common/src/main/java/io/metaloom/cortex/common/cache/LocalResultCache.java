package io.metaloom.cortex.common.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, thread-safe, access-order LRU for a node's in-heap "already computed" cache.
 *
 * <p>
 * This mirrors the skip cache pioneered by the fingerprint node: an expensive node (whisper, captioning, quality, ...) remembers the results it
 * produced during this worker's lifetime so a second pass over the same media re-emits the cached value instead of recomputing it. The cache is
 * intentionally <b>non-durable</b> - the durable copy of the result lives in Loom (persisted alongside the computation). Keying is by media path or
 * SHA-512, whichever the node has cheaply to hand.
 * </p>
 *
 * @param <V> the cached value type
 */
public class LocalResultCache<V> {

	private final Map<String, V> map;

	/**
	 * @param maxSize the maximum number of entries retained before the least-recently-used entry is evicted
	 */
	public LocalResultCache(int maxSize) {
		this.map = Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
				return size() > maxSize;
			}
		});
	}

	/**
	 * Return the cached value for the key, or null when absent.
	 */
	public V get(String key) {
		return map.get(key);
	}

	/**
	 * Return true when a value is cached for the key.
	 */
	public boolean has(String key) {
		return map.containsKey(key);
	}

	/**
	 * Store the computed value for the key.
	 */
	public void put(String key, V value) {
		map.put(key, value);
	}

}
