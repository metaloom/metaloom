package io.metaloom.cortex.node.imagemanip;

/**
 * The operations {@link ImageManipulationNode} can apply, in the order the pipeline author lists them.
 *
 * <p>
 * They are deliberately orthogonal: each one answers a different question about the frame, and none of them is a preset for another. The one recipe
 * that <em>looks</em> like a missing member - the vertical-video-syndrome fix - is {@link #ASPECT} with {@code aspectMode = PAD} and
 * {@code padFill = BLUR}, because a blurred backdrop is a way of filling padding rather than a different way of framing.
 * </p>
 */
public enum Op {

	/**
	 * Apply the file's EXIF {@code Orientation} so the stored pixels become upright.
	 *
	 * <p>
	 * Must be first when present - every later op reasons about a coordinate space this one redefines, and so do the detection boxes. See
	 * {@link ManipulationGeometry#transform(Orientation, ManipulationGeometry.Rect, int, int)}.
	 * </p>
	 */
	AUTOROTATE,

	/** Cut a fixed rectangle, addressed in relative 0-1 coordinates so it means the same thing at every resolution. */
	CROP,

	/** Frame the subjects delivered on the {@code detections} input port rather than the geometric centre. */
	SUBJECT_CROP,

	/** Force a target aspect ratio, either by cutting the long axis or by padding the short one. */
	ASPECT,

	/** Bound the result by its long edge. Aspect is always preserved. */
	RESIZE;

	/**
	 * Parse an operation name, case- and whitespace-insensitively.
	 *
	 * @param value the token from the {@code operations} option
	 * @return the operation, or {@code null} when the name is not one
	 */
	public static Op parse(String value) {
		if (value == null) {
			return null;
		}
		String name = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
		for (Op op : values()) {
			if (op.name().equals(name)) {
				return op;
			}
		}
		return null;
	}
}
