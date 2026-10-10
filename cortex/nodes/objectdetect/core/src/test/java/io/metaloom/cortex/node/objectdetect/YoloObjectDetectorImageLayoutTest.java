package io.metaloom.cortex.node.objectdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import org.junit.jupiter.api.Test;

/**
 * {@code CVUtils.bufferedImageToMat} copies raster bytes verbatim into a {@code CV_8UC3} Mat, so whatever reaches it has to be packed BGR already.
 * A greyscale, alpha or int-packed image used to reach the model as scrambled pixels. No natives needed - this only checks the conversion.
 */
class YoloObjectDetectorImageLayoutTest {

	@Test
	void testBgrImageIsPassedThroughUntouched() {
		BufferedImage bgr = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
		assertSame(bgr, YoloObjectDetector.toBgr(bgr));
	}

	@Test
	void testIntRgbIsRepackedAsBgrBytes() {
		BufferedImage rgb = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
		rgb.setRGB(0, 0, 0xFF0000); // pure red
		rgb.setRGB(1, 0, 0x0000FF); // pure blue

		BufferedImage bgr = YoloObjectDetector.toBgr(rgb);

		assertEquals(BufferedImage.TYPE_3BYTE_BGR, bgr.getType());
		byte[] bytes = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		assertEquals(6, bytes.length, "three bytes per pixel, the CV_8UC3 layout");
		// Red pixel: B=0, G=0, R=255.
		assertEquals(0, bytes[0] & 0xFF);
		assertEquals(0, bytes[1] & 0xFF);
		assertEquals(255, bytes[2] & 0xFF);
		// Blue pixel: B=255, G=0, R=0.
		assertEquals(255, bytes[3] & 0xFF);
		assertEquals(0, bytes[5] & 0xFF);
	}

	@Test
	void testGreyscaleBecomesThreeChannels() {
		BufferedImage grey = new BufferedImage(5, 4, BufferedImage.TYPE_BYTE_GRAY);
		grey.getRaster().setSample(0, 0, 0, 200);

		BufferedImage bgr = YoloObjectDetector.toBgr(grey);

		byte[] bytes = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		assertEquals(5 * 4 * 3, bytes.length, "a one-byte-per-pixel raster filled only a third of the Mat");
	}

	@Test
	void testTransparencyIsFlattenedToThreeChannels() {
		BufferedImage argb = new BufferedImage(3, 3, BufferedImage.TYPE_4BYTE_ABGR);

		BufferedImage bgr = YoloObjectDetector.toBgr(argb);

		byte[] bytes = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		assertEquals(3 * 3 * 3, bytes.length, "four bytes per pixel overran the Mat and shifted every channel");
		// Fully transparent lands on white.
		assertEquals(255, bytes[0] & 0xFF);
	}
}
