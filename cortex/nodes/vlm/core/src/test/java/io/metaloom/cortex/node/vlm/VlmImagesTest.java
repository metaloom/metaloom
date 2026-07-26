package io.metaloom.cortex.node.vlm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Covers the image pre-processing the node does before it talks to the model. Rotation correctness in particular is invisible through a mock endpoint -
 * the mock will happily answer whatever image it is sent - so it is asserted directly here.
 */
public class VlmImagesTest {

	/**
	 * A 40x20 image whose top-left quadrant is red and the rest white, so a rotation can be told apart from a no-op by where the red corner ends up.
	 */
	private BufferedImage marked() {
		BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 40, 20);
		g.setColor(Color.RED);
		g.fillRect(0, 0, 20, 10);
		g.dispose();
		return image;
	}

	private static boolean isRed(BufferedImage image, int x, int y) {
		Color c = new Color(image.getRGB(x, y));
		return c.getRed() > 200 && c.getGreen() < 80 && c.getBlue() < 80;
	}

	@Test
	public void testDownscaleCapsTheLongestSide() {
		BufferedImage scaled = VlmImages.downscale(marked(), 20);

		assertEquals(20, scaled.getWidth());
		assertEquals(10, scaled.getHeight(), "Aspect ratio must be preserved");
	}

	/**
	 * Upscaling would cost the model tokens without adding detail, so an image already within budget comes back untouched.
	 */
	@Test
	public void testDownscaleLeavesSmallImagesAlone() {
		BufferedImage source = marked();
		assertSame(source, VlmImages.downscale(source, 1288));
		assertSame(source, VlmImages.downscale(source, 0), "0 disables scaling");
	}

	@Test
	public void testRotate90SwapsAxesAndTurnsClockwise() {
		BufferedImage rotated = VlmImages.rotate(marked(), 90);

		assertEquals(20, rotated.getWidth());
		assertEquals(40, rotated.getHeight());
		// A clockwise quarter turn moves the top-left mark to the top-right.
		assertTrue(isRed(rotated, 15, 5), "Expected the mark in the top-right after a clockwise 90 turn");
		assertFalse(isRed(rotated, 5, 5), "The top-left must no longer carry the mark");
	}

	@Test
	public void testRotate180KeepsDimensionsAndFlipsCorners() {
		BufferedImage rotated = VlmImages.rotate(marked(), 180);

		assertEquals(40, rotated.getWidth());
		assertEquals(20, rotated.getHeight());
		assertTrue(isRed(rotated, 35, 15), "Expected the mark in the bottom-right after a 180 turn");
		assertFalse(isRed(rotated, 5, 5));
	}

	@Test
	public void testRotate270() {
		BufferedImage rotated = VlmImages.rotate(marked(), 270);

		assertEquals(20, rotated.getWidth());
		assertEquals(40, rotated.getHeight());
		assertTrue(isRed(rotated, 5, 35), "Expected the mark in the bottom-left after a counter-clockwise quarter turn");
	}

	/**
	 * Rotation is only meaningful as a quarter turn; anything else must be a no-op rather than a skewed image.
	 */
	@Test
	public void testRotateIgnoresNonQuarterTurns() {
		BufferedImage source = marked();
		assertSame(source, VlmImages.rotate(source, 0));
		assertSame(source, VlmImages.rotate(source, 45));
	}

	/**
	 * PNGs with an alpha channel are common for screenshots and scans; the JPEG writer rejects them, so they must be flattened first.
	 */
	@Test
	public void testEncodesImagesWithAlpha() throws IOException {
		BufferedImage withAlpha = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		String dataUri = VlmImages.toJpegDataUri(withAlpha);

		assertTrue(dataUri.startsWith("data:image/jpeg;base64,"));
		assertTrue(dataUri.length() > "data:image/jpeg;base64,".length(), "Expected encoded image bytes");
	}
}
