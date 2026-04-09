package io.metaloom.cortex.api.node.payload;

import java.util.List;

/**
 * Payload carrying a list of tags. Produced by auto-tagging nodes, label classifiers,
 * or any node that emits categorical annotations.
 */
public interface TagsPayload extends Payload {

	/**
	 * The tags.
	 */
	List<String> tags();

	static TagsPayload of(List<String> tags) {
		List<String> copy = List.copyOf(tags);
		return () -> copy;
	}
}
