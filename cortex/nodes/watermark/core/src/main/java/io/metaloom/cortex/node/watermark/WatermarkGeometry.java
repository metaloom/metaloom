package io.metaloom.cortex.node.watermark;

/**
 * Turns the node's relative placement options into absolute pixels.
 *
 * <p>
 * This is the only piece of logic the image path and the video path share, and the only place a placement bug can live - so it is a pure function with
 * no dependency on ImageIO, ffmpeg or the node itself, and it is what {@code WatermarkGeometryTest} exercises directly.
 * </p>
 *
 * <h2>Why the relative factors address the inset box</h2>
 *
 * <p>
 * The obvious reading of "relative X" is <code>x = mediaW * relX</code>, which puts the watermark's <em>left edge</em> at that fraction of the frame -
 * so <code>relX = 1.0</code> pushes the whole overlay outside the picture and <code>relX = 0.5</code> centres its left edge rather than the overlay.
 * Both are useless as defaults.
 * </p>
 *
 * <p>
 * Instead the factors address the box the overlay can slide within, <code>mediaW - overlayW</code>: <code>0.0</code> is flush left, <code>1.0</code> is
 * flush right, <code>0.5</code> is exactly centred, and the overlay can never leave the frame for any factor in range.
 * </p>
 */
public final class WatermarkGeometry {

	private WatermarkGeometry() {
	}

	/**
	 * The absolute pixel rectangle the watermark occupies in the media frame.
	 *
	 * @param x      left edge, pixels from the left of the frame
	 * @param y      top edge, pixels from the top of the frame
	 * @param width  scaled overlay width in pixels, always &gt;= 1
	 * @param height scaled overlay height in pixels, always &gt;= 1
	 */
	public record Placement(int x, int y, int width, int height) {
	}

	/**
	 * Resolve where and how large the watermark goes.
	 *
	 * <p>
	 * The overlay is first scaled so its width is <code>scale</code> of the <em>media</em> width (aspect preserved), then clamped so it never exceeds
	 * the frame in either axis, then positioned within the remaining slack by the two relative factors.
	 * </p>
	 *
	 * @param mediaWidth   frame width in pixels
	 * @param mediaHeight  frame height in pixels
	 * @param sourceWidth  the watermark image's own width in pixels
	 * @param sourceHeight the watermark image's own height in pixels
	 * @param scale        target overlay width as a fraction of the media width; <code>0</code> (or less) keeps the overlay's native size
	 * @param relX         horizontal placement, <code>0.0</code> flush left to <code>1.0</code> flush right
	 * @param relY         vertical placement, <code>0.0</code> flush top to <code>1.0</code> flush bottom
	 * @return the resolved rectangle
	 * @throws IllegalArgumentException when any dimension is not positive
	 */
	public static Placement place(int mediaWidth, int mediaHeight, int sourceWidth, int sourceHeight, double scale, double relX, double relY) {
		if (mediaWidth <= 0 || mediaHeight <= 0) {
			throw new IllegalArgumentException("Media dimensions must be positive but were " + mediaWidth + "x" + mediaHeight);
		}
		if (sourceWidth <= 0 || sourceHeight <= 0) {
			throw new IllegalArgumentException("Watermark dimensions must be positive but were " + sourceWidth + "x" + sourceHeight);
		}

		int width = scale > 0 ? (int) Math.round(mediaWidth * scale) : sourceWidth;
		width = Math.max(1, Math.min(width, mediaWidth));
		int height = Math.max(1, (int) Math.round(width * (double) sourceHeight / sourceWidth));

		// A very wide-and-short frame can make the aspect-preserved height overflow even though the width fits. Give up the requested width rather than
		// the aspect ratio: a squashed logo is more obviously wrong than a smaller one.
		if (height > mediaHeight) {
			height = mediaHeight;
			width = Math.max(1, Math.min((int) Math.round(height * (double) sourceWidth / sourceHeight), mediaWidth));
		}

		int x = (int) Math.round((mediaWidth - width) * clamp(relX));
		int y = (int) Math.round((mediaHeight - height) * clamp(relY));
		return new Placement(x, y, width, height);
	}

	/**
	 * Clamp a relative factor into <code>[0,1]</code>. Options validation rejects out-of-range values, but a node constructed programmatically bypasses
	 * that, and silently placing the overlay off-frame is worse than silently clamping it.
	 */
	private static double clamp(double value) {
		return Math.max(0d, Math.min(1d, value));
	}
}
