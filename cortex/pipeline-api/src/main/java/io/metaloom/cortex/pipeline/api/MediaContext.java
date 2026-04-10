package io.metaloom.cortex.pipeline.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Carrier for a media item and its accumulated node results as it flows through
 * the reactive pipeline graph. Each node appends its result via {@link #withResult}.
 *
 * <p>Instances are immutable — every mutation returns a new context.</p>
 */
public class MediaContext {

	private final LoomMedia media;
	private final Map<String, NodeResult> results;

	public MediaContext(LoomMedia media) {
		this(media, Collections.emptyMap());
	}

	public MediaContext(LoomMedia media, Map<String, NodeResult> results) {
		this.media = media;
		this.results = Collections.unmodifiableMap(new HashMap<>(results));
	}

	public LoomMedia getMedia() {
		return media;
	}

	/**
	 * All accumulated upstream results, keyed by node id.
	 */
	public Map<String, NodeResult> getUpstreamResults() {
		return results;
	}

	/**
	 * Get the result for a specific node id.
	 */
	public NodeResult getResult(String nodeId) {
		return results.get(nodeId);
	}

	/**
	 * Create a new context with the given node result appended.
	 */
	public MediaContext withResult(String nodeId, NodeResult result) {
		Map<String, NodeResult> merged = new HashMap<>(results);
		merged.put(nodeId, result);
		return new MediaContext(media, merged);
	}

	/**
	 * Merge results from another context for the same media item. Results from the
	 * other context overwrite this context's results for the same node id.
	 */
	public MediaContext merge(MediaContext other) {
		if (!this.media.absolutePath().equals(other.media.absolutePath())) {
			throw new IllegalArgumentException("Cannot merge contexts for different media items");
		}
		Map<String, NodeResult> merged = new HashMap<>(results);
		merged.putAll(other.results);
		return new MediaContext(media, merged);
	}

	@Override
	public String toString() {
		return "MediaContext{media=" + media.absolutePath() + ", results=" + results.keySet() + "}";
	}
}
