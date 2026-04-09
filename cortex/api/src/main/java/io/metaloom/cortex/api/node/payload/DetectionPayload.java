package io.metaloom.cortex.api.node.payload;

import java.util.List;

/**
 * Payload containing detection results — bounding boxes with frame and confidence data.
 * Produced by face-detection or object-detection nodes and consumed by downstream nodes
 * that constrain processing to detected regions.
 */
public interface DetectionPayload extends Payload {

	/**
	 * The list of detections.
	 */
	List<Detection> detections();

	static DetectionPayload of(List<Detection> detections) {
		List<Detection> copy = List.copyOf(detections);
		return () -> copy;
	}
}
