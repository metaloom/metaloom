package io.metaloom.cortex.node.sam2;

/**
 * A prompt box, in <strong>XYXY</strong> and in the pixel space of the image actually posted to the
 * sidecar.
 *
 * <p>
 * Both halves of that sentence are load-bearing. Upstream detectors emit XYWH
 * ({@code {"bbox":{"x","y","w","h"}}}), and they measure against the source image or the video's
 * native frame — neither of which is what SAM 2 sees after the node downscales to {@code maxDim}.
 * Converting once, here, keeps a single place where the two conventions meet.
 * </p>
 *
 * @param x1    left edge
 * @param y1    top edge
 * @param x2    right edge, exclusive of nothing — simply {@code x1 + width}
 * @param y2    bottom edge
 * @param label the upstream class name, carried through onto the produced mask; may be null
 * @param objId the tracking id for {@link Sam2Mode#TRACK}; null for still-image prompts
 */
public record Sam2Box(double x1, double y1, double x2, double y2, String label, Integer objId) {

	/**
	 * Build a box from an upstream XYWH detection, rescaling it into the posted image's space.
	 *
	 * @param x      left edge in source space
	 * @param y      top edge in source space
	 * @param width  box width in source space
	 * @param height box height in source space
	 * @param scale  posted-image size divided by source size; 1.0 when nothing was downscaled
	 * @param label  the upstream class name, or null
	 * @param objId  the tracking id, or null
	 * @return the rescaled XYXY box
	 */
	public static Sam2Box fromXywh(double x, double y, double width, double height, double scale, String label, Integer objId) {
		return new Sam2Box(x * scale, y * scale, (x + width) * scale, (y + height) * scale, label, objId);
	}

	/** Whether the box encloses any area at all. A zero-width box is a sidecar 400, so drop it here. */
	public boolean isValid() {
		return x2 > x1 && y2 > y1;
	}
}
