package io.metaloom.cortex.node.objectdetect;

import io.metaloom.cortex.api.node.payload.BoundingBox;

/**
 * One detected object, located in the media as a whole rather than in a single frame.
 *
 * <p>
 * This is {@link ObjectDetection} plus the frame it was found on, and the type the rest of the node
 * works in: the box is always in <strong>native frame pixels</strong>, scaled back up if inference
 * ran on a downscaled copy. Images use frame 0, matching the {@code detection} table's convention.
 * </p>
 *
 * @param box        where in the native-resolution frame, absolute pixels
 * @param frameIndex 0-based frame index within the media; 0 for images
 * @param confidence the detector's score, 0.0 – 1.0
 * @param classId    the model's class index
 * @param label      the class name, or null when the id is outside the loaded label set
 */
public record DetectedObject(BoundingBox box, int frameIndex, float confidence, int classId, String label) {

	/**
	 * The name to report for this object.
	 *
	 * <p>
	 * A detection whose class id falls outside the labels file still has a box worth keeping, so it is
	 * reported under its id rather than dropped or written out with a null label — the
	 * {@code detection.label} column is indexed and searched, and a null there is indistinguishable
	 * from "this producer does not do labels".
	 * </p>
	 */
	public String labelOrId() {
		return label != null && !label.isBlank() ? label : "class-" + classId;
	}
}
