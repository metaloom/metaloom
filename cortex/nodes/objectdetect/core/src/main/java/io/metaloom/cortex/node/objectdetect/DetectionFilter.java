package io.metaloom.cortex.node.objectdetect;

import java.util.Set;

import io.metaloom.cortex.api.node.payload.BoundingBox;

/**
 * Turns a raw engine detection into one the node will keep, or into nothing.
 *
 * <p>
 * Shared by both paths on purpose. The image path and the video path apply the same confidence and
 * class rules, and holding them in two places is how the two quietly diverge — {@code facedetect}'s
 * {@code maxFaceAngle} gate applies to its video path only, so the same frame yields faces as a file
 * and none as a video.
 * </p>
 */
public final class DetectionFilter {

	private DetectionFilter() {
	}

	/**
	 * Apply the confidence and class filters, and lift the box to native resolution.
	 *
	 * @param detection  what the engine reported
	 * @param frameIndex the frame it was found on; 0 for an image
	 * @param scale      native width ÷ inference width, or 1.0 when inference ran at full size
	 * @param options    the configured thresholds
	 * @return the detection to keep, or null when it was filtered out
	 */
	public static DetectedObject accept(ObjectDetection detection, int frameIndex, double scale, ObjectDetectNodeOptions options) {
		if (detection.confidence() < options.getMinConfidence()) {
			return null;
		}
		String label = detection.label();
		Set<String> filter = options.normalizedClassFilter();
		// An unlabelled detection cannot satisfy a filter that names classes: there is nothing to match
		// against, and keeping it would mean a "person only" pass returning things that are not people.
		if (!filter.isEmpty() && (label == null || !filter.contains(label.toLowerCase()))) {
			return null;
		}
		return new DetectedObject(rescale(detection.box(), scale), frameIndex, detection.confidence(), detection.classId(), label);
	}

	/**
	 * Scale a box measured on a downscaled frame back to native frame pixels.
	 */
	public static BoundingBox rescale(BoundingBox box, double scale) {
		if (scale == 1.0d) {
			return box;
		}
		return new BoundingBox(
			(int) Math.round(box.x() * scale),
			(int) Math.round(box.y() * scale),
			(int) Math.round(box.width() * scale),
			(int) Math.round(box.height() * scale));
	}
}
