package io.metaloom.cortex.api.node.payload;

/**
 * A single detection within a frame — for example a detected face or object.
 *
 * @param boundingBox the location of the detection
 * @param frameIndex  the 0-based frame index within the media (0 for images)
 * @param confidence  the detection confidence score (0.0 – 1.0)
 * @param label       an optional label for the detection (e.g. "person", "car")
 */
public record Detection(BoundingBox boundingBox, int frameIndex, float confidence, String label) {

	public Detection(BoundingBox boundingBox, int frameIndex, float confidence) {
		this(boundingBox, frameIndex, confidence, null);
	}
}
