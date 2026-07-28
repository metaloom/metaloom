package io.metaloom.cortex.node.color;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Reads an image and reduces a region of it to a bounded, deterministic set of CIELAB points.
 *
 * <h2>Why stride sampling and not {@code Graphics2D} downscaling</h2>
 *
 * This is a deliberate divergence from the {@code DepthImages.downscale} pattern the other
 * ImageIO-based nodes use, and the reason is specific to colour:
 *
 * <ul>
 * <li><strong>Interpolation invents colours that are not in the image.</strong> A red-and-blue
 * striped flag downscaled bilinearly is purple, and the node would confidently report purple as the
 * dominant colour. Averaging is the right reduction for a depth map and the wrong one for a colour
 * histogram.</li>
 * <li><strong>It destroys the alpha gate.</strong> Interpolating over {@code TYPE_INT_ARGB} bleeds
 * the RGB of fully transparent pixels - usually black or white - into their opaque neighbours,
 * before anything has had a chance to discard them.</li>
 * </ul>
 *
 * Stride sampling has neither problem, needs no intermediate image, and is exactly reproducible -
 * which is half of what makes the k-means result reproducible (see {@link LabKMeans}).
 *
 * <h2>Alpha</h2>
 *
 * Transparent pixels are <strong>skipped</strong>, never flattened onto a background. Flattening
 * onto white - which {@code DepthImages.toOpaque} does, correctly for its own purpose - would make
 * every logo on a transparent background come back "white".
 */
public final class PixelSampler {

	private PixelSampler() {
	}

	/**
	 * Decode an image file.
	 *
	 * @param file the file
	 * @return the decoded image
	 * @throws IOException when no registered reader can decode it. Notably this is what happens for
	 *                     a CMYK JPEG, matching the behaviour of the facedetect and quality nodes
	 */
	public static BufferedImage read(File file) throws IOException {
		BufferedImage image = ImageIO.read(file);
		if (image == null) {
			throw new IOException("No image reader could decode " + file.getAbsolutePath());
		}
		return image;
	}

	/**
	 * Sample a region into a flat CIELAB array.
	 *
	 * @param image          the decoded image
	 * @param box            the region, already clamped to the image bounds
	 * @param maxSamples     upper bound on the number of pixels to visit; the stride is derived
	 *                       from it, so the real count is at or below this
	 * @param alphaThreshold pixels whose alpha is below this are skipped
	 * @return the points as {@code [l0, a0, b0, l1, a1, b1, ...]} in raster order
	 */
	public static double[] sampleLab(BufferedImage image, Box box, int maxSamples, int alphaThreshold) {
		if (box.isEmpty() || maxSamples < 1) {
			return new double[0];
		}
		int step = stride(box, maxSamples);
		int capacity = ((box.w() + step - 1) / step) * ((box.h() + step - 1) / step);
		double[] out = new double[capacity * 3];

		int p = 0;
		for (int y = box.y(); y < box.y() + box.h(); y += step) {
			for (int x = box.x(); x < box.x() + box.w(); x += step) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) < alphaThreshold) {
					continue;
				}
				Lab lab = ColorSpaces.packedToLab(argb);
				out[p++] = lab.l();
				out[p++] = lab.a();
				out[p++] = lab.b();
			}
		}
		if (p == out.length) {
			return out;
		}
		double[] trimmed = new double[p];
		System.arraycopy(out, 0, trimmed, 0, p);
		return trimmed;
	}

	/**
	 * Count distinct packed RGB values over the same sample the {@link #sampleLab} call would
	 * visit. Used to cap k before k-means++ runs, so a picture with three colours never asks for
	 * five clusters and lets the seeder pick duplicate centres.
	 *
	 * @param image          the decoded image
	 * @param box            the region
	 * @param maxSamples     the same bound passed to {@link #sampleLab}
	 * @param alphaThreshold the same alpha gate
	 * @param cap            stop counting once this many distinct values are seen
	 * @return the distinct count, at most {@code cap}
	 */
	public static int distinctColors(BufferedImage image, Box box, int maxSamples, int alphaThreshold, int cap) {
		if (box.isEmpty() || maxSamples < 1 || cap < 1) {
			return 0;
		}
		int step = stride(box, maxSamples);
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (int y = box.y(); y < box.y() + box.h(); y += step) {
			for (int x = box.x(); x < box.x() + box.w(); x += step) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) < alphaThreshold) {
					continue;
				}
				seen.add(argb & 0xFFFFFF);
				if (seen.size() >= cap) {
					return cap;
				}
			}
		}
		return seen.size();
	}

	/**
	 * The sampling stride, applied to both axes.
	 *
	 * @param box        the region
	 * @param maxSamples the sample budget
	 * @return the stride, at least 1
	 */
	static int stride(Box box, int maxSamples) {
		return Math.max(1, (int) Math.ceil(Math.sqrt(box.area() / (double) maxSamples)));
	}
}
