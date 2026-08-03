package io.metaloom.cortex.node.imagemanip;

import java.util.List;
import java.util.Locale;

/**
 * All of the node's arithmetic, with none of its pixels.
 *
 * <p>
 * Nothing here touches {@code ImageIO}, {@code Graphics2D} or the filesystem: it is {@link Rect} in, {@link Rect} out. That is the
 * {@code WatermarkGeometry} precedent, and it matters more here because five operations compose and the composition is where the defects are. A pure
 * class means every one of them is pinned by a test that runs in microseconds and asserts integers rather than pixels.
 * </p>
 *
 * <p>
 * <strong>Every rectangle produced here is inside its frame and at least 1x1.</strong> Callers hand the results straight to
 * {@code BufferedImage.getSubimage}, which throws on anything else.
 * </p>
 */
public final class ManipulationGeometry {

	/** A rectangle in absolute pixels, top-left origin. */
	public record Rect(int x, int y, int w, int h) {

		/** The horizontal centre, in continuous coordinates. */
		public double centerX() {
			return x + w / 2.0d;
		}

		/** The vertical centre, in continuous coordinates. */
		public double centerY() {
			return y + h / 2.0d;
		}

		/** Whether this rectangle covers the whole of a {@code width x height} frame. */
		public boolean covers(int width, int height) {
			return x == 0 && y == 0 && w == width && h == height;
		}
	}

	/** A width/height pair, for the operations that produce a canvas rather than a window into one. */
	public record Size(int w, int h) {
	}

	private ManipulationGeometry() {
	}

	// ── orientation ──────────────────────────────────────────────────────

	/**
	 * The frame size after applying {@code orientation}.
	 *
	 * <p>
	 * Width and height trade places for the four quarter-turn orientations. Forgetting that is the quiet half of the autorotate trap: the pixels come
	 * out right and every rectangle computed against the old dimensions is then wrong.
	 * </p>
	 */
	public static Size transform(Orientation orientation, int width, int height) {
		return orientation.swapsAxes() ? new Size(height, width) : new Size(width, height);
	}

	/**
	 * Carry a rectangle through the same orientation the pixels go through.
	 *
	 * <p>
	 * 🔴 This is what keeps {@code SUBJECT_CROP} honest after an {@code AUTOROTATE}. {@code FacedetectNode} decodes with {@code ImageIO.read}, which
	 * ignores EXIF, so its boxes are in <em>stored</em> pixel space - exactly the space autorotation redefines. A box that is not carried through here
	 * lands somewhere plausible and wrong, with no error anywhere.
	 * </p>
	 *
	 * @param orientation the orientation being applied to the pixels
	 * @param rect        a rectangle in the source frame
	 * @param width       source frame width, before the transform
	 * @param height      source frame height, before the transform
	 * @return the same region, addressed in the transformed frame
	 */
	public static Rect transform(Orientation orientation, Rect rect, int width, int height) {
		int x = rect.x();
		int y = rect.y();
		int w = rect.w();
		int h = rect.h();

		// The mirror happens first, in source space, matching how Orientation encodes the pair.
		if (orientation.mirrored()) {
			x = width - x - w;
		}

		return switch (orientation.degrees()) {
			case 90 -> new Rect(height - y - h, x, h, w);
			case 180 -> new Rect(width - x - w, height - y - h, w, h);
			case 270 -> new Rect(y, width - x - w, h, w);
			default -> new Rect(x, y, w, h);
		};
	}

	// ── crops ────────────────────────────────────────────────────────────

	/**
	 * A crop window addressed in relative 0-1 coordinates.
	 *
	 * <pre>
	 * x = round(W * relX)      w = round(W * relW)
	 * y = round(H * relY)      h = round(H * relH)
	 * </pre>
	 *
	 * Relative, because a rectangle in absolute pixels is right at exactly one resolution - the same argument
	 * {@code WatermarkGeometry} makes for placement.
	 */
	public static Rect relativeCrop(int width, int height, double relX, double relY, double relW, double relH) {
		int x = (int) Math.round(width * relX);
		int y = (int) Math.round(height * relY);
		int w = (int) Math.round(width * relW);
		int h = (int) Math.round(height * relH);
		return clamp(new Rect(x, y, w, h), width, height);
	}

	/**
	 * Push a rectangle inside the frame, keeping its size where possible and shrinking it where not.
	 *
	 * @return a rectangle fully inside {@code width x height}, never smaller than 1x1
	 */
	public static Rect clamp(Rect rect, int width, int height) {
		int w = Math.max(1, Math.min(rect.w(), width));
		int h = Math.max(1, Math.min(rect.h(), height));
		int x = Math.max(0, Math.min(rect.x(), width - w));
		int y = Math.max(0, Math.min(rect.y(), height - h));
		return new Rect(x, y, w, h);
	}

	/**
	 * Shift a rectangle.
	 *
	 * <p>
	 * Used to carry the subject boxes through every operation that moves the frame's origin: a crop makes their coordinates relative to the cut window,
	 * a pad makes them relative to the enlarged canvas. A chain that cropped and then subject-cropped would otherwise frame the boxes as if the first
	 * crop had not happened.
	 * </p>
	 */
	public static Rect translate(Rect rect, int dx, int dy) {
		return new Rect(rect.x() + dx, rect.y() + dy, rect.w(), rect.h());
	}

	/** Scale a rectangle, for carrying boxes through a resize. Never produces a zero-sized side. */
	public static Rect scale(Rect rect, double sx, double sy) {
		return new Rect((int) Math.round(rect.x() * sx), (int) Math.round(rect.y() * sy),
			Math.max(1, (int) Math.round(rect.w() * sx)), Math.max(1, (int) Math.round(rect.h() * sy)));
	}

	/** Whether a rectangle has any pixels at all inside a {@code width x height} frame. */
	public static boolean intersects(Rect rect, int width, int height) {
		return rect.x() < width && rect.y() < height && rect.x() + rect.w() > 0 && rect.y() + rect.h() > 0;
	}

	/**
	 * The bounding box of every rectangle given.
	 *
	 * <p>
	 * The <em>union</em> is what later ops grow and frame, not each box in turn: a group photo has to stay one crop, and padding the boxes individually
	 * would frame whichever one happened to be last.
	 * </p>
	 *
	 * @param rects the boxes, at least one
	 * @return their bounding box
	 * @throws IllegalArgumentException when the list is empty
	 */
	public static Rect union(List<Rect> rects) {
		if (rects == null || rects.isEmpty()) {
			throw new IllegalArgumentException("Cannot union an empty list of rectangles");
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Rect rect : rects) {
			minX = Math.min(minX, rect.x());
			minY = Math.min(minY, rect.y());
			maxX = Math.max(maxX, rect.x() + rect.w());
			maxY = Math.max(maxY, rect.y() + rect.h());
		}
		return new Rect(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
	}

	/**
	 * Grow a rectangle by a fraction of its own size on every side.
	 *
	 * <p>
	 * Relative to the rectangle rather than to the frame, so a tight head-and-shoulders box gains breathing room proportional to the head - a fraction
	 * of the frame would swallow a small face and barely move a large one.
	 * </p>
	 *
	 * @param padding fraction of the rectangle's own width/height added to each side; 0 is a no-op
	 */
	public static Rect pad(Rect rect, double padding) {
		if (padding <= 0d) {
			return rect;
		}
		int dx = (int) Math.round(rect.w() * padding);
		int dy = (int) Math.round(rect.h() * padding);
		return new Rect(rect.x() - dx, rect.y() - dy, rect.w() + 2 * dx, rect.h() + 2 * dy);
	}

	/**
	 * Grow a rectangle until it has the target aspect ratio, keeping it centred and inside the frame.
	 *
	 * <p>
	 * <strong>Growth first, shrink only if it will not fit.</strong> Reaching the aspect by cutting the long axis would crop the very subjects the
	 * rectangle was built around, which is the opposite of what a subject crop is for. Only when the grown rectangle cannot fit in the frame at all is
	 * the long axis given up.
	 * </p>
	 *
	 * @param aspect target width/height; {@code <= 0} returns the rectangle unchanged
	 */
	public static Rect expandToAspect(Rect rect, double aspect, int width, int height) {
		if (aspect <= 0d) {
			return clamp(rect, width, height);
		}
		double cx = rect.centerX();
		double cy = rect.centerY();

		int w = rect.w();
		int h = rect.h();
		double current = (double) w / (double) h;
		if (current < aspect) {
			w = (int) Math.round(h * aspect);
		} else if (current > aspect) {
			h = (int) Math.round(w / aspect);
		}

		// Too large for the frame: give up size, never the aspect - a rectangle that is merely smaller
		// still frames the subject, one with the wrong ratio defeats the whole operation.
		if (w > width || h > height) {
			Size fitted = largestWithin(width, height, aspect);
			w = fitted.w();
			h = fitted.h();
		}

		w = Math.max(1, w);
		h = Math.max(1, h);
		int x = (int) Math.round(cx - w / 2.0d);
		int y = (int) Math.round(cy - h / 2.0d);
		return clamp(new Rect(x, y, w, h), width, height);
	}

	/**
	 * The largest centred rectangle of the target aspect that fits in the frame - the {@code aspectMode = CROP} window.
	 *
	 * @param aspect target width/height; {@code <= 0} returns the whole frame
	 */
	public static Rect centreAspect(int width, int height, double aspect) {
		if (aspect <= 0d) {
			return new Rect(0, 0, width, height);
		}
		Size size = largestWithin(width, height, aspect);
		int x = (width - size.w()) / 2;
		int y = (height - size.h()) / 2;
		return clamp(new Rect(x, y, size.w(), size.h()), width, height);
	}

	/** The largest {@code aspect}-shaped size that fits inside {@code width x height}. */
	private static Size largestWithin(int width, int height, double aspect) {
		int w;
		int h;
		if ((double) width / (double) height > aspect) {
			h = height;
			w = (int) Math.round(height * aspect);
		} else {
			w = width;
			h = (int) Math.round(width / aspect);
		}
		return new Size(Math.max(1, Math.min(w, width)), Math.max(1, Math.min(h, height)));
	}

	/**
	 * The smallest canvas of the target aspect that contains the whole frame - the {@code aspectMode = PAD} canvas.
	 *
	 * <p>
	 * This is the vertical-video-syndrome geometry: a 3:4 portrait against a 16:9 target produces a canvas as tall as the source and far wider, and the
	 * margins on either side are what {@code padFill} then fills.
	 * </p>
	 *
	 * @param aspect target width/height; {@code <= 0} returns the frame unchanged
	 */
	public static Size padToAspect(int width, int height, double aspect) {
		if (aspect <= 0d) {
			return new Size(width, height);
		}
		if ((double) width / (double) height > aspect) {
			return new Size(width, Math.max(height, (int) Math.round(width / aspect)));
		}
		return new Size(Math.max(width, (int) Math.round(height * aspect)), height);
	}

	// ── resize ───────────────────────────────────────────────────────────

	/**
	 * Bound a frame by its long edge, aspect preserved.
	 *
	 * @param maxLongEdge  the bound; {@code <= 0} disables the operation
	 * @param allowUpscale whether a frame already smaller than the bound is enlarged to meet it. Off by default: enlarging invents detail and inflates
	 *                     the artifact for no gain
	 */
	public static Size resizeBounds(int width, int height, int maxLongEdge, boolean allowUpscale) {
		if (maxLongEdge <= 0) {
			return new Size(width, height);
		}
		int longEdge = Math.max(width, height);
		double scale = (double) maxLongEdge / (double) longEdge;
		if (scale >= 1.0d && !allowUpscale) {
			return new Size(width, height);
		}
		return new Size(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
	}

	// ── aspect parsing ───────────────────────────────────────────────────

	/**
	 * Parse a {@code W:H} aspect ratio.
	 *
	 * <p>
	 * A bare decimal ({@code 1.7778}) is accepted too, but {@code 16:9} is the form to write: it is what an author means, and it does not quietly become
	 * a different ratio through rounding.
	 * </p>
	 *
	 * @param value the option value; blank means "keep the current ratio"
	 * @return the ratio as width/height, or {@code 0} for "keep"
	 * @throws IllegalArgumentException when the value is neither blank, {@code W:H} nor a positive decimal
	 */
	public static double parseAspect(String value) {
		if (value == null || value.isBlank()) {
			return 0d;
		}
		String text = value.trim();
		int colon = text.indexOf(':');
		try {
			if (colon >= 0) {
				double w = Double.parseDouble(text.substring(0, colon).trim());
				double h = Double.parseDouble(text.substring(colon + 1).trim());
				if (w <= 0d || h <= 0d) {
					throw new IllegalArgumentException("Aspect ratio parts must be positive: " + value);
				}
				return w / h;
			}
			double ratio = Double.parseDouble(text);
			if (ratio <= 0d) {
				throw new IllegalArgumentException("Aspect ratio must be positive: " + value);
			}
			return ratio;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not an aspect ratio, expected W:H such as 16:9: " + value, e);
		}
	}

	/** Whether {@code value} is a legal {@code targetAspect}, for {@code validate()}. */
	public static boolean isAspect(String value) {
		try {
			parseAspect(value);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Parse an {@code #RRGGBB} colour into a packed opaque ARGB value.
	 *
	 * @throws IllegalArgumentException when the value is not six hex digits with an optional leading {@code #}
	 */
	public static int parseColor(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Colour must not be null");
		}
		String hex = value.trim().toLowerCase(Locale.ROOT);
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		if (hex.length() != 6 || !hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
			throw new IllegalArgumentException("Not a #RRGGBB colour: " + value);
		}
		return 0xFF000000 | Integer.parseInt(hex, 16);
	}

	/** Whether {@code value} is a legal {@code #RRGGBB} colour, for {@code validate()}. */
	public static boolean isColor(String value) {
		try {
			parseColor(value);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
