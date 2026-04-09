package io.metaloom.cortex.api.node.payload;

import java.util.List;

/**
 * Payload containing scene segmentation results. Produced by scene-detection nodes
 * that split a video into temporal segments.
 */
public interface ScenesPayload extends Payload {

	/**
	 * The detected scenes in chronological order.
	 */
	List<Scene> scenes();

	static ScenesPayload of(List<Scene> scenes) {
		List<Scene> copy = List.copyOf(scenes);
		return () -> copy;
	}
}
