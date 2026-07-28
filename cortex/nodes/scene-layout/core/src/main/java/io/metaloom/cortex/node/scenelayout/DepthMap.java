package io.metaloom.cortex.node.scenelayout;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * A decoded depth map in NEARNESS units: {@code 1.0} is nearest to the camera, {@code 0.0} farthest.
 *
 * <p>
 * The on-disk artifact produced by the {@code depthmap} node is a 16-bit grayscale PNG in which
 * {@code 65535} is nearest. Java decodes that as {@link BufferedImage#TYPE_USHORT_GRAY} and
 * {@link Raster#getSample} returns {@code 0..65535}, which this class divides down to
 * {@code [0,1]}. 8-bit maps are also accepted (divided by 255) so a hand-made or downgraded
 * artifact still works, at reduced precision.
 * </p>
 *
 * <p>
 * 🔴 The map's dimensions are <em>not</em> the source image's - the image is downscaled to the
 * node's {@code maxDim} before inference. Boxes must be projected before sampling; that is what
 * {@link #projectFromImage} exists for. Skipping it throws no exception, it just samples the
 * wrong pixels.
 * </p>
 */
public class DepthMap {

	private final float[] nearness;
	private final int width;
	private final int height;
	private final int imageWidth;
	private final int imageHeight;

	/** Ascending copy of the samples, built lazily for quantile queries. */
	private float[] sorted;

	DepthMap(float[] nearness, int width, int height, int imageWidth, int imageHeight) {
		this.nearness = nearness;
		this.width = width;
		this.height = height;
		this.imageWidth = imageWidth;
		this.imageHeight = imageHeight;
	}

	/**
	 * Read a depth-map PNG from disk.
	 *
	 * @param file        the 16-bit grayscale PNG produced by the depthmap node
	 * @param imageWidth  width of the source image the boxes are measured against
	 * @param imageHeight height of that source image
	 * @return the decoded map
	 * @throws IOException when the file cannot be read or decoded
	 */
	public static DepthMap read(File file, int imageWidth, int imageHeight) throws IOException {
		BufferedImage image = ImageIO.read(file);
		if (image == null) {
			throw new IOException("No image reader could decode the depth map " + file.getAbsolutePath());
		}
		int width = image.getWidth();
		int height = image.getHeight();

		// A 16-bit grayscale PNG decodes to TYPE_USHORT_GRAY; anything else we normalise by its
		// own bit depth rather than refusing, so a downgraded artifact degrades instead of failing.
		int maxValue = image.getType() == BufferedImage.TYPE_USHORT_GRAY ? 65535 : 255;

		Raster raster = image.getRaster();
		float[] values = new float[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				values[y * width + x] = raster.getSample(x, y, 0) / (float) maxValue;
			}
		}
		return new DepthMap(values, width, height, imageWidth, imageHeight);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/**
	 * Project a box from source-image coordinates into this map's coordinates.
	 *
	 * @param box a box in image space
	 * @return the same box in map space
	 */
	public BoxF projectFromImage(BoxF box) {
		if (imageWidth <= 0 || imageHeight <= 0) {
			return box;
		}
		return box.scale((double) width / imageWidth, (double) height / imageHeight);
	}

	/**
	 * Nearness at a map pixel. Coordinates outside the map are clamped to the edge.
	 *
	 * @param x map-space column
	 * @param y map-space row
	 * @return nearness in [0, 1], 1 = nearest
	 */
	public double nearnessAt(int x, int y) {
		int cx = Math.max(0, Math.min(width - 1, x));
		int cy = Math.max(0, Math.min(height - 1, y));
		return nearness[cy * width + cx];
	}

	/**
	 * A quantile of the whole scene's depth distribution, used to cut the foreground and background
	 * bands.
	 *
	 * @param q the quantile in [0, 1]
	 * @return the nearness value at that quantile
	 */
	public double sceneQuantile(double q) {
		if (sorted == null) {
			sorted = nearness.clone();
			Arrays.sort(sorted);
		}
		if (sorted.length == 0) {
			return 0;
		}
		int idx = (int) Math.round(Math.max(0, Math.min(1, q)) * (sorted.length - 1));
		return sorted[idx];
	}

	/**
	 * Collect every sample inside a map-space box.
	 *
	 * @param box the region, in map coordinates
	 * @return the samples, empty when the box covers no pixel
	 */
	public float[] samplesIn(BoxF box) {
		int x0 = Math.max(0, (int) Math.floor(box.x()));
		int y0 = Math.max(0, (int) Math.floor(box.y()));
		int x1 = Math.min(width, (int) Math.ceil(box.right()));
		int y1 = Math.min(height, (int) Math.ceil(box.bottom()));
		if (x1 <= x0 || y1 <= y0) {
			return new float[0];
		}
		float[] out = new float[(x1 - x0) * (y1 - y0)];
		int n = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				out[n++] = nearness[y * width + x];
			}
		}
		return n == out.length ? out : Arrays.copyOf(out, n);
	}
}
