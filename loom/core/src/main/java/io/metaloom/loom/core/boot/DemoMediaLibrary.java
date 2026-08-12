package io.metaloom.loom.core.boot;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the checked-in demo media ({@code demo-content/}) for {@link DemoDatabaseInitializer}.
 *
 * <p>
 * The directory is resolved once, from {@code LOOM_DEMO_CONTENT_DIR} or by probing (see
 * {@link io.metaloom.loom.api.options.DemoOptions}). Only the demo container ships it; everywhere else this library reports
 * {@link #isAvailable() unavailable} and the initializer paints its images instead.
 * </p>
 *
 * <p>
 * Every accessor answers {@code null} rather than throwing. The seed runs inside a catch-all in {@code BootstrapInitializer} that swallows the
 * exception and never retries, so one unreadable photograph must cost that one picture and nothing else.
 * </p>
 */
public class DemoMediaLibrary {

	private static final Logger log = LoggerFactory.getLogger(DemoMediaLibrary.class);

	/**
	 * Longest edge of a seeded image, in pixels.
	 *
	 * <p>
	 * Loom has no thumbnail service — a preview <em>is</em> the stored binary, which the asset grid loads once per tile. Seeding the 2560 px
	 * originals would make the browser's first screen a ~16 MB download. 1600 px is what the painted demo images have always been, so the demo's
	 * page weight does not change when the pictures become real.
	 * </p>
	 */
	public static final int MAX_IMAGE_EDGE = 1600;

	/** Edge length of an account picture or person image, matching the shipped {@code demo/portraits/} crops. */
	public static final int PORTRAIT_EDGE = 512;

	private static final float JPEG_QUALITY = 0.82f;

	private final Path root;

	/**
	 * @param root
	 *            the demo content directory, or null when there is none
	 */
	public DemoMediaLibrary(Path root) {
		this.root = root != null && Files.isDirectory(root) ? root : null;
		if (root != null && this.root == null) {
			log.warn("Demo content directory {} does not exist — the demo will paint its images instead", root);
		}
	}

	/** Whether real media is available at all. When false every accessor answers null. */
	public boolean isAvailable() {
		return root != null;
	}

	public Path root() {
		return root;
	}

	/**
	 * Resolve a file below the content directory.
	 *
	 * @return the path, or null when the library is unavailable or the file is missing
	 */
	public Path file(String relativePath) {
		if (root == null) {
			return null;
		}
		Path path = root.resolve(relativePath);
		if (!Files.isRegularFile(path)) {
			log.warn("Demo content file {} is missing — the entry it backs will be skipped", path);
			return null;
		}
		return path;
	}

	/**
	 * One demo photograph, resized to {@link #MAX_IMAGE_EDGE} and re-encoded as JPEG.
	 *
	 * @return the JPEG bytes, or null when the file is missing
	 */
	public byte[] image(String relativePath) {
		Path path = file(relativePath);
		if (path == null) {
			return null;
		}
		return encodeFit(read(path), MAX_IMAGE_EDGE, path);
	}

	/**
	 * A square, centred portrait crop, scaled to {@link #PORTRAIT_EDGE}.
	 *
	 * <p>
	 * Used for the already-square 640x640 files. {@code zoom} tightens the crop around the middle — 1.0 keeps the whole frame, 0.7 takes the middle
	 * 70%. That is how a person's gallery gets a second framing of the same face out of a single file.
	 * </p>
	 *
	 * @return the JPEG bytes, or null when the file is missing
	 */
	public byte[] portrait(String relativePath, double zoom) {
		Path path = file(relativePath);
		if (path == null) {
			return null;
		}
		BufferedImage source = read(path);
		if (source == null) {
			return null;
		}
		int edge = (int) Math.round(Math.min(source.getWidth(), source.getHeight()) * clamp(zoom));
		int x = (source.getWidth() - edge) / 2;
		int y = (source.getHeight() - edge) / 2;
		return encodeSquare(source.getSubimage(x, y, edge, edge), path);
	}

	/**
	 * A square portrait cut at an explicit offset, scaled to {@link #PORTRAIT_EDGE}.
	 *
	 * <p>
	 * For the uncropped originals, where the face is not in the middle of the frame. The geometry for the three Pexels portraits is recorded in
	 * {@code loom/core/src/main/resources/demo/portraits/README.txt} and is reused here rather than re-derived.
	 * </p>
	 *
	 * @param edge
	 *            side length of the square to cut, in source pixels
	 * @return the JPEG bytes, or null when the file is missing or the geometry falls outside it
	 */
	public byte[] portraitCrop(String relativePath, int edge, int x, int y) {
		Path path = file(relativePath);
		if (path == null) {
			return null;
		}
		BufferedImage source = read(path);
		if (source == null) {
			return null;
		}
		if (x < 0 || y < 0 || x + edge > source.getWidth() || y + edge > source.getHeight()) {
			log.warn("Demo portrait crop {}:{}:{}:{} does not fit {}x{} in {} — falling back to a centred crop", edge, edge, x, y,
				source.getWidth(), source.getHeight(), path);
			return portrait(relativePath, 1.0);
		}
		return encodeSquare(source.getSubimage(x, y, edge, edge), path);
	}

	/**
	 * A square crop taken from a normalised region of a photograph — the same 0..1 coordinates a detection box carries.
	 *
	 * <p>
	 * The region is expanded to a square around its centre and then padded by {@code margin} (0.4 = 40% wider than the box), because a face box is
	 * tight on the face and a picture of somebody wants their hair and chin in it.
	 * </p>
	 *
	 * @return the JPEG bytes, or null when the file is missing
	 */
	public byte[] regionCrop(String relativePath, double x, double y, double width, double height, double margin) {
		Path path = file(relativePath);
		if (path == null) {
			return null;
		}
		BufferedImage source = read(path);
		if (source == null) {
			return null;
		}
		double centreX = (x + width / 2) * source.getWidth();
		double centreY = (y + height / 2) * source.getHeight();
		double edge = Math.max(width * source.getWidth(), height * source.getHeight()) * (1 + margin);

		int side = (int) Math.round(Math.min(edge, Math.min(source.getWidth(), source.getHeight())));
		int left = (int) Math.round(centreX - side / 2.0);
		int top = (int) Math.round(centreY - side / 2.0);
		left = Math.max(0, Math.min(left, source.getWidth() - side));
		top = Math.max(0, Math.min(top, source.getHeight() - side));

		return encodeSquare(source.getSubimage(left, top, side, side), path);
	}

	private static double clamp(double zoom) {
		return Math.max(0.1, Math.min(1.0, zoom));
	}

	private static BufferedImage read(Path path) {
		try {
			BufferedImage image = ImageIO.read(path.toFile());
			if (image == null) {
				log.warn("No image reader could decode {} — the entry it backs will be skipped", path);
			}
			return image;
		} catch (IOException e) {
			log.warn("Could not read demo image {} — the entry it backs will be skipped", path, e);
			return null;
		}
	}

	/**
	 * Scale to fit within {@code maxEdge} — never up — and encode as JPEG.
	 *
	 * <p>
	 * On any failure the file's own bytes are returned unchanged. A native image without the JPEG writer, or a JRE without the AWT pieces, then
	 * still seeds a real photograph — it just seeds a larger one — rather than losing the picture to a plumbing problem. That substitution is only
	 * sound here, where the whole frame is what was wanted; {@link #encodeSquare} must not make it.
	 * </p>
	 */
	private static byte[] encodeFit(BufferedImage source, int maxEdge, Path fallback) {
		if (source == null) {
			return null;
		}
		int longest = Math.max(source.getWidth(), source.getHeight());
		double factor = longest <= maxEdge ? 1.0 : (double) maxEdge / longest;
		int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(source.getHeight() * factor));

		try {
			byte[] bytes = toJpeg(copyAsRgb(source, width, height));
			if (bytes != null) {
				return bytes;
			}
		} catch (Exception e) {
			log.warn("Could not re-encode demo image {} — storing the original bytes instead", fallback, e);
		}
		try {
			return Files.readAllBytes(fallback);
		} catch (IOException e) {
			log.warn("Could not read demo image {} — the entry it backs will be skipped", fallback, e);
			return null;
		}
	}

	/**
	 * Resize a square crop to exactly {@link #PORTRAIT_EDGE} and encode it as JPEG.
	 *
	 * <p>
	 * Exactly, in both directions: a person's gallery and an account picture are a fixed size, and a tighter framing of a 640 px portrait is smaller
	 * than that. There is no falling back to the file's own bytes — those are the uncropped picture, which is not this picture — so a failure here
	 * costs the crop.
	 * </p>
	 */
	private static byte[] encodeSquare(BufferedImage crop, Path source) {
		if (crop == null) {
			return null;
		}
		try {
			return toJpeg(copyAsRgb(crop, PORTRAIT_EDGE, PORTRAIT_EDGE));
		} catch (Exception e) {
			log.warn("Could not encode the portrait cut from {} — the picture will be skipped", source, e);
			return null;
		}
	}

	private static BufferedImage copyAsRgb(BufferedImage source, int width, int height) {
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(source, 0, 0, width, height, null);
		g.dispose();
		return target;
	}

	private static byte[] toJpeg(BufferedImage image) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			return null;
		}
		ImageWriter writer = writers.next();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
				writer.setOutput(stream);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return out.toByteArray();
		} finally {
			writer.dispose();
		}
	}

}
