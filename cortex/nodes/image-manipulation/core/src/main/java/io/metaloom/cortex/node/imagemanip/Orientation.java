package io.metaloom.cortex.node.imagemanip;

/**
 * The eight EXIF orientations, as the transform needed to make the stored pixels upright.
 *
 * <p>
 * Four of them - {@link #MIRROR_HORIZONTAL}, {@link #MIRROR_VERTICAL}, {@link #TRANSPOSE}, {@link #TRANSVERSE} - are <strong>mirrored</strong>, not
 * merely rotated. A rotation-only implementation does not fail on them; it silently produces a flipped image, which is the whole reason this is an
 * eight-member enum rather than a switch on 90/180/270. {@code VlmImages.rotate} in the vlm node is exactly that rotation-only helper and covers four
 * of these eight.
 * </p>
 *
 * <p>
 * {@link #swapsAxes()} is the other half of the trap: for the four values that rotate by a quarter turn the output is {@code H x W}, not {@code W x H},
 * and everything downstream - crop rectangles, aspect targets, detection boxes - is measured against the swapped frame.
 * </p>
 */
public enum Orientation {

	/** EXIF 1. The stored pixels are already upright. */
	NORMAL(1, 0, false),

	/** EXIF 2. Flipped left-to-right. */
	MIRROR_HORIZONTAL(2, 0, true),

	/** EXIF 3. Upside down. */
	ROTATE_180(3, 180, false),

	/** EXIF 4. Flipped top-to-bottom, i.e. mirrored then turned half way. */
	MIRROR_VERTICAL(4, 180, true),

	/** EXIF 5. Mirrored across the main diagonal - a horizontal flip followed by a three-quarter turn clockwise. */
	TRANSPOSE(5, 270, true),

	/** EXIF 6. Stored rotated counter-clockwise; display needs a quarter turn clockwise. */
	ROTATE_90(6, 90, false),

	/** EXIF 7. Mirrored across the anti-diagonal - a horizontal flip followed by a quarter turn clockwise. */
	TRANSVERSE(7, 90, true),

	/** EXIF 8. Stored rotated clockwise; display needs a quarter turn counter-clockwise. */
	ROTATE_270(8, 270, false);

	private final int exifValue;

	private final int degrees;

	private final boolean mirrored;

	Orientation(int exifValue, int degrees, boolean mirrored) {
		this.exifValue = exifValue;
		this.degrees = degrees;
		this.mirrored = mirrored;
	}

	/** The raw EXIF tag value, 1-8. */
	public int exifValue() {
		return exifValue;
	}

	/** Clockwise rotation applied after the optional mirror: 0, 90, 180 or 270. */
	public int degrees() {
		return degrees;
	}

	/** Whether the transform includes a horizontal flip before the rotation. */
	public boolean mirrored() {
		return mirrored;
	}

	/** Whether width and height trade places, i.e. the rotation is a quarter turn. */
	public boolean swapsAxes() {
		return degrees == 90 || degrees == 270;
	}

	/** Whether applying this orientation changes anything at all. */
	public boolean isIdentity() {
		return this == NORMAL;
	}

	/**
	 * Map a raw EXIF tag value onto an orientation.
	 *
	 * <p>
	 * An absent, zero or out-of-range value is {@link #NORMAL} rather than an error: a JPEG with a corrupt orientation byte is a file to process, not a
	 * file to reject, and every reader in the wild treats it that way.
	 * </p>
	 *
	 * @param exifValue the tag value, or null
	 * @return the orientation, never null
	 */
	public static Orientation ofExif(Integer exifValue) {
		if (exifValue == null) {
			return NORMAL;
		}
		for (Orientation orientation : values()) {
			if (orientation.exifValue == exifValue) {
				return orientation;
			}
		}
		return NORMAL;
	}
}
