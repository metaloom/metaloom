package io.metaloom.cortex.node.scenelayout;

/**
 * An axis-aligned bounding box in floating-point coordinates.
 *
 * <p>
 * Float rather than int because boxes get projected from image space into depth-map space,
 * and rounding at that step is what turns a box near the frame edge into a box slightly
 * outside it.
 * </p>
 *
 * @param x left edge
 * @param y top edge
 * @param w width
 * @param h height
 */
public record BoxF(double x, double y, double w, double h) {

	public double right() {
		return x + w;
	}

	public double bottom() {
		return y + h;
	}

	public double centerX() {
		return x + w / 2.0;
	}

	public double centerY() {
		return y + h / 2.0;
	}

	public double area() {
		return Math.max(0, w) * Math.max(0, h);
	}

	/**
	 * Scale the box by independent x and y factors, for projecting image-space boxes into
	 * depth-map space.
	 *
	 * @param fx horizontal factor
	 * @param fy vertical factor
	 * @return the scaled box
	 */
	public BoxF scale(double fx, double fy) {
		return new BoxF(x * fx, y * fy, w * fx, h * fy);
	}

	/**
	 * Shrink the box toward its centre by {@code inset} of its width and height on each side.
	 * An inset of 0.25 keeps the central 50% by both dimensions.
	 *
	 * <p>
	 * This is what stops a box's background corners from contaminating the object's depth
	 * statistic. Insets that would collapse the box are clamped so the result stays non-empty.
	 * </p>
	 *
	 * @param inset fraction to remove from each side, in [0, 0.5)
	 * @return the inset box
	 */
	public BoxF core(double inset) {
		double clamped = Math.max(0, Math.min(0.49, inset));
		double dx = w * clamped;
		double dy = h * clamped;
		return new BoxF(x + dx, y + dy, Math.max(1e-6, w - 2 * dx), Math.max(1e-6, h - 2 * dy));
	}

	/**
	 * Area of the intersection with another box, or 0 when they do not overlap.
	 *
	 * @param other the other box
	 * @return the intersection area
	 */
	public double intersectionArea(BoxF other) {
		double ix = Math.min(right(), other.right()) - Math.max(x, other.x);
		double iy = Math.min(bottom(), other.bottom()) - Math.max(y, other.y);
		if (ix <= 0 || iy <= 0) {
			return 0;
		}
		return ix * iy;
	}

	/**
	 * Whether the two boxes overlap when projected onto the x axis.
	 *
	 * @param other the other box
	 * @return true when their horizontal spans intersect
	 */
	public boolean overlapsX(BoxF other) {
		return Math.min(right(), other.right()) > Math.max(x, other.x);
	}

	/**
	 * Whether the two boxes overlap when projected onto the y axis.
	 *
	 * @param other the other box
	 * @return true when their vertical spans intersect
	 */
	public boolean overlapsY(BoxF other) {
		return Math.min(bottom(), other.bottom()) > Math.max(y, other.y);
	}

	/**
	 * Shortest gap between the two boxes' edges, measured on whichever axis they are separated.
	 * Returns 0 when they overlap.
	 *
	 * @param other the other box
	 * @return the gap in the same units as the box
	 */
	public double gap(BoxF other) {
		double dx = Math.max(0, Math.max(x, other.x) - Math.min(right(), other.right()));
		double dy = Math.max(0, Math.max(y, other.y) - Math.min(bottom(), other.bottom()));
		return Math.hypot(dx, dy);
	}
}
