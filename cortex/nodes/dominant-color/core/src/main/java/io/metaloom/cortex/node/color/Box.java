package io.metaloom.cortex.node.color;

/**
 * An axis-aligned region of an image in whole pixels.
 *
 * <p>
 * Integer rather than floating point, unlike {@code BoxF} in the scene-layout node: a box is only
 * ever used here to index a raster, so it must be resolved to whole pixels exactly once - at
 * {@link #clampTo(int, int)} - rather than repeatedly at every read.
 * </p>
 *
 * @param x left edge, inclusive
 * @param y top edge, inclusive
 * @param w width
 * @param h height
 */
public record Box(int x, int y, int w, int h) {

	/**
	 * Build a box from floating-point edges, flooring the origin and ceiling the far edge.
	 *
	 * <p>
	 * Rounding both edges the same way would let a sub-pixel box collapse to nothing through
	 * double truncation; widening it instead keeps a small detection usable.
	 * </p>
	 *
	 * @param x left edge
	 * @param y top edge
	 * @param w width
	 * @param h height
	 * @return the pixel box
	 */
	public static Box ofBounds(double x, double y, double w, double h) {
		int left = (int) Math.floor(x);
		int top = (int) Math.floor(y);
		int right = (int) Math.ceil(x + w);
		int bottom = (int) Math.ceil(y + h);
		return new Box(left, top, right - left, bottom - top);
	}

	/**
	 * @return the pixel count, never negative
	 */
	public long area() {
		return (long) Math.max(0, w) * Math.max(0, h);
	}

	/**
	 * @return true when the box covers no pixels
	 */
	public boolean isEmpty() {
		return area() == 0;
	}

	/**
	 * Intersect the box with the image bounds.
	 *
	 * @param imageWidth  the image width
	 * @param imageHeight the image height
	 * @return the clamped box, possibly empty when the box lies entirely outside the image
	 */
	public Box clampTo(int imageWidth, int imageHeight) {
		int left = Math.max(0, Math.min(x, imageWidth));
		int top = Math.max(0, Math.min(y, imageHeight));
		int right = Math.max(left, Math.min(x + w, imageWidth));
		int bottom = Math.max(top, Math.min(y + h, imageHeight));
		return new Box(left, top, right - left, bottom - top);
	}
}
