package io.metaloom.cortex.node.imagemanip;

import io.metaloom.cortex.fs.AtomicFiles;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Size;

/**
 * The pixel half of the node: everything that actually moves bytes around a raster.
 *
 * <p>
 * Plain ImageIO and {@code Graphics2D} throughout, so the module needs no OpenCV native runtime and the node and its tests stay runnable on any machine
 * - the same trade {@code WatermarkImages}, {@code DepthImages} and {@code VlmImages} make.
 * </p>
 *
 * <p>
 * <strong>Nothing here ever writes into its argument.</strong> The source image comes from {@code MediaArtifacts.decodedImage}, which is shared with
 * every other node in the segment; drawing into it would change what all of them measure. Each operation allocates its own destination, including
 * {@link #crop}, where the obvious {@code getSubimage} would hand back a view onto the shared raster.
 * </p>
 */
public final class ManipulationImages {

	/**
	 * Long edge of the buffer the backdrop blur is computed on.
	 *
	 * <p>
	 * A box blur wide enough to be unrecognisable on a 6000px frame costs a radius in the hundreds. Downscaling first, blurring small and scaling back
	 * up is visually indistinguishable from the same blur at full size - the high frequencies the blur is there to destroy are exactly what the
	 * downscale already discards - and turns a multi-second operation into a few milliseconds.
	 * </p>
	 */
	private static final int BLUR_WORK_EDGE = 160;

	private ManipulationImages() {
	}

	// ── geometry-applying operations ─────────────────────────────────────

	/**
	 * Apply an EXIF orientation so the stored pixels become upright.
	 *
	 * <p>
	 * The transform is built as <em>mirror first, then rotate</em>, matching how {@link Orientation} encodes the pair; the four mirrored orientations
	 * are what a rotation-only implementation gets silently wrong.
	 * </p>
	 *
	 * @return a new image, {@code height x width} for the quarter-turn orientations; the argument itself when the orientation is the identity
	 */
	public static BufferedImage orient(BufferedImage source, Orientation orientation) {
		if (orientation.isIdentity()) {
			return source;
		}
		int width = source.getWidth();
		int height = source.getHeight();
		Size out = ManipulationGeometry.transform(orientation, width, height);

		AffineTransform transform = new AffineTransform();
		switch (orientation.degrees()) {
			case 90 -> {
				transform.translate(height, 0);
				transform.rotate(Math.PI / 2);
			}
			case 180 -> {
				transform.translate(width, height);
				transform.rotate(Math.PI);
			}
			case 270 -> {
				transform.translate(0, width);
				transform.rotate(3 * Math.PI / 2);
			}
			default -> {
				// No rotation; the mirror below is the whole transform.
			}
		}
		if (orientation.mirrored()) {
			// concatenate() post-multiplies, so this runs *before* the rotation above - which is the order
			// the EXIF table specifies.
			transform.translate(width, 0);
			transform.scale(-1, 1);
		}

		BufferedImage result = blank(out.w(), out.h());
		Graphics2D g = result.createGraphics();
		try {
			quality(g);
			g.drawImage(source, transform, null);
		} finally {
			g.dispose();
		}
		return result;
	}

	/**
	 * Cut a window out of the image.
	 *
	 * <p>
	 * A copy, not {@code getSubimage}: that returns a view sharing the parent's raster, and the parent here is the segment-shared decoded image.
	 * </p>
	 */
	public static BufferedImage crop(BufferedImage source, Rect rect) {
		Rect safe = ManipulationGeometry.clamp(rect, source.getWidth(), source.getHeight());
		if (safe.covers(source.getWidth(), source.getHeight())) {
			return source;
		}
		BufferedImage result = blank(safe.w(), safe.h());
		Graphics2D g = result.createGraphics();
		try {
			g.drawImage(source, 0, 0, safe.w(), safe.h(), safe.x(), safe.y(), safe.x() + safe.w(), safe.y() + safe.h(), null);
		} finally {
			g.dispose();
		}
		return result;
	}

	/** Resample the image to an exact size. Bilinear with quality rendering hints, matching {@code WatermarkImages.composite}. */
	public static BufferedImage resize(BufferedImage source, Size size) {
		if (size.w() == source.getWidth() && size.h() == source.getHeight()) {
			return source;
		}
		BufferedImage result = blank(size.w(), size.h());
		Graphics2D g = result.createGraphics();
		try {
			quality(g);
			g.drawImage(source, 0, 0, size.w(), size.h(), null);
		} finally {
			g.dispose();
		}
		return result;
	}

	/** Centre the image on a larger canvas filled with a flat colour - the classic letterbox. */
	public static BufferedImage padWithColor(BufferedImage source, Size canvas, int argb) {
		if (canvas.w() <= source.getWidth() && canvas.h() <= source.getHeight()) {
			return source;
		}
		BufferedImage result = blank(canvas.w(), canvas.h());
		Graphics2D g = result.createGraphics();
		try {
			g.setColor(new Color(argb, true));
			g.fillRect(0, 0, canvas.w(), canvas.h());
			drawCentred(g, source, canvas);
		} finally {
			g.dispose();
		}
		return result;
	}

	/**
	 * Centre the image on a larger canvas whose margins are a blurred enlargement of the image itself.
	 *
	 * <p>
	 * <strong>This is the vertical-video-syndrome fix.</strong> A portrait frame against a 16:9 target gets margins that belong to the picture instead
	 * of two black bars.
	 * </p>
	 *
	 * <p>
	 * The backdrop is scaled to <em>cover</em> the canvas and then overscanned by {@code zoom}: at exactly cover size the backdrop's own edges sit on
	 * the canvas edges, and any rounding puts a sliver of background inside the frame. Hence a default zoom above 1.
	 * </p>
	 *
	 * @param radius box-blur radius, expressed against the full-size backdrop
	 * @param zoom   overscan factor, {@code >= 1}
	 */
	public static BufferedImage padWithBlur(BufferedImage source, Size canvas, int radius, double zoom) {
		if (canvas.w() <= source.getWidth() && canvas.h() <= source.getHeight()) {
			return source;
		}
		double cover = Math.max((double) canvas.w() / source.getWidth(), (double) canvas.h() / source.getHeight()) * Math.max(1.0d, zoom);
		int coverW = Math.max(1, (int) Math.round(source.getWidth() * cover));
		int coverH = Math.max(1, (int) Math.round(source.getHeight() * cover));

		// Blur small, then enlarge. See BLUR_WORK_EDGE.
		double shrink = Math.min(1.0d, (double) BLUR_WORK_EDGE / Math.max(coverW, coverH));
		int workW = Math.max(1, (int) Math.round(coverW * shrink));
		int workH = Math.max(1, (int) Math.round(coverH * shrink));
		BufferedImage work = resize(toOpaqueCopy(source), new Size(workW, workH));
		BufferedImage blurred = boxBlur(work, Math.max(1, (int) Math.round(radius * shrink)));

		BufferedImage result = blank(canvas.w(), canvas.h());
		Graphics2D g = result.createGraphics();
		try {
			quality(g);
			int bx = (canvas.w() - coverW) / 2;
			int by = (canvas.h() - coverH) / 2;
			g.drawImage(blurred, bx, by, coverW, coverH, null);
			drawCentred(g, source, canvas);
		} finally {
			g.dispose();
		}
		return result;
	}

	// ── encoding ─────────────────────────────────────────────────────────

	/**
	 * Composite the image onto an opaque background.
	 *
	 * <p>
	 * 🔴 Required before writing JPEG. {@code ImageIO.write} on a {@code TYPE_INT_ARGB} raster does not reject the alpha channel - it writes four
	 * components into a three-component format, and the result reads back inverted or magenta-tinted depending on the JDK.
	 * </p>
	 */
	public static BufferedImage flatten(BufferedImage source, int argb) {
		if (!source.getColorModel().hasAlpha()) {
			return source;
		}
		BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = result.createGraphics();
		try {
			g.setColor(new Color(argb, false));
			g.fillRect(0, 0, source.getWidth(), source.getHeight());
			g.drawImage(source, 0, 0, null);
		} finally {
			g.dispose();
		}
		return result;
	}

	/**
	 * Encode the image and publish it atomically.
	 *
	 * <p>
	 * The bytes land in a {@code .part} sibling that is then moved into place, so a crashed worker can never leave a truncated artifact that a later run
	 * serves from its cache or a sink uploads.
	 * </p>
	 *
	 * @param image   the image to write; flattened first when {@code format} cannot carry alpha
	 * @param target  destination path, its parent created if missing
	 * @param format  the encoding
	 * @param quality JPEG quality, 0-1; ignored for PNG
	 * @param background flatten colour used when the image has alpha and the format does not
	 * @throws IOException when the directory cannot be created or no writer is available
	 */
	public static void write(BufferedImage image, Path target, OutputFormat format, double quality, int background) throws IOException {
		Files.createDirectories(target.getParent());
		BufferedImage encodable = format.supportsAlpha() ? image : flatten(image, background);
		Path part = AtomicFiles.partFor(target);
		try {
			if (format == OutputFormat.JPEG) {
				writeJpeg(encodable, part, quality);
			} else if (!ImageIO.write(encodable, format.writerFormat(), part.toFile())) {
				throw new IOException("No " + format.writerFormat() + " writer available");
			}
			AtomicFiles.move(part, target);
		} finally {
			Files.deleteIfExists(part);
		}
	}

	private static void writeJpeg(BufferedImage image, Path target, double quality) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("No JPEG writer available");
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
			ImageWriteParam params = writer.getDefaultWriteParam();
			if (params.canWriteCompressed()) {
				params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				params.setCompressionQuality((float) Math.max(0.01d, Math.min(1.0d, quality)));
			}
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), params);
		} finally {
			writer.dispose();
		}
	}

	// ── internals ────────────────────────────────────────────────────────

	/**
	 * A separable box blur, two passes over an int raster.
	 *
	 * <p>
	 * Not a Gaussian: at the radii this node uses, on a buffer already downscaled to {@link #BLUR_WORK_EDGE} and then enlarged again, the two are
	 * indistinguishable and this one has no kernel to build.
	 * </p>
	 */
	static BufferedImage boxBlur(BufferedImage source, int radius) {
		if (radius < 1) {
			return source;
		}
		int width = source.getWidth();
		int height = source.getHeight();
		int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
		int[] scratch = new int[pixels.length];
		blurPass(pixels, scratch, width, height, radius);
		blurPass(scratch, pixels, height, width, radius);

		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		result.setRGB(0, 0, width, height, pixels, 0, width);
		return result;
	}

	/**
	 * One horizontal blur pass that writes its output <strong>transposed</strong>.
	 *
	 * <p>
	 * Running it twice therefore blurs both axes and restores the original orientation, which is why there is no separate vertical implementation to
	 * keep in step with this one.
	 * </p>
	 */
	private static void blurPass(int[] in, int[] out, int width, int height, int radius) {
		int window = radius * 2 + 1;
		for (int y = 0; y < height; y++) {
			int row = y * width;
			int r = 0;
			int g = 0;
			int b = 0;
			// Prime the accumulator with the window centred on x = 0, edge pixels clamped.
			for (int i = -radius; i <= radius; i++) {
				int rgb = in[row + clampIndex(i, width)];
				r += (rgb >> 16) & 0xFF;
				g += (rgb >> 8) & 0xFF;
				b += rgb & 0xFF;
			}
			for (int x = 0; x < width; x++) {
				out[x * height + y] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
				int leaving = in[row + clampIndex(x - radius, width)];
				int entering = in[row + clampIndex(x + radius + 1, width)];
				r += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
				g += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
				b += (entering & 0xFF) - (leaving & 0xFF);
			}
		}
	}

	private static int clampIndex(int index, int length) {
		return index < 0 ? 0 : Math.min(index, length - 1);
	}

	/** A fresh ARGB raster. Always ARGB: an indexed or read-only raster from {@code ImageIO.read} quantises everything drawn onto it. */
	private static BufferedImage blank(int width, int height) {
		return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
	}

	private static BufferedImage toOpaqueCopy(BufferedImage source) {
		BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = result.createGraphics();
		try {
			g.drawImage(source, 0, 0, null);
		} finally {
			g.dispose();
		}
		return result;
	}

	private static void drawCentred(Graphics2D g, BufferedImage source, Size canvas) {
		g.drawImage(source, (canvas.w() - source.getWidth()) / 2, (canvas.h() - source.getHeight()) / 2, null);
	}

	private static void quality(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	}
}
