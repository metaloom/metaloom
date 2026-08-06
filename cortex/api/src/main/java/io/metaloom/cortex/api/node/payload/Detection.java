package io.metaloom.cortex.api.node.payload;

/**
 * A single detection within a frame — for example a detected face or object.
 *
 * @param boundingBox the location of the detection
 * @param frameIndex  the 0-based frame index within the media (0 for images)
 * @param confidence  the detection confidence score (0.0 – 1.0)
 * @param label       an optional label for the detection (e.g. "person", "car")
 * @param embedding   an optional recognition vector for this detection, or null when the producer computed none. Carried on the detection rather than
 *                    in a parallel list so a vector cannot drift onto the wrong box — the boxes and the vectors are filtered, sorted and truncated
 *                    together at several points, and any of those steps applied to one list and not the other is silent, plausible corruption
 */
public record Detection(BoundingBox boundingBox, int frameIndex, float confidence, String label, float[] embedding) {

	public Detection(BoundingBox boundingBox, int frameIndex, float confidence, String label) {
		this(boundingBox, frameIndex, confidence, label, null);
	}

	public Detection(BoundingBox boundingBox, int frameIndex, float confidence) {
		this(boundingBox, frameIndex, confidence, null, null);
	}

	/** Whether this detection carries a usable recognition vector. */
	public boolean hasEmbedding() {
		return embedding != null && embedding.length > 0;
	}
}
