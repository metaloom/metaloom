package io.metaloom.cortex.node.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import org.junit.jupiter.api.Test;

/**
 * The blurriness Mat is {@code CV_8UC3} and {@code CVUtils.bufferedImageToMat} copies raster bytes verbatim, so the image has to be packed BGR first.
 * A greyscale or transparent image used to be measured on scrambled memory. Pure Java - no natives needed.
 */
class QualityNodeImageLayoutTest {

	@Test
	void testBgrImageIsPassedThroughUntouched() {
		BufferedImage bgr = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
		assertSame(bgr, QualityNode.toBgr(bgr));
	}

	@Test
	void testGreyscaleBecomesThreeChannels() {
		BufferedImage grey = new BufferedImage(5, 4, BufferedImage.TYPE_BYTE_GRAY);

		BufferedImage bgr = QualityNode.toBgr(grey);

		assertEquals(BufferedImage.TYPE_3BYTE_BGR, bgr.getType());
		assertEquals(5 * 4 * 3, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData().length);
	}

	@Test
	void testAlphaImageBecomesThreeChannels() {
		BufferedImage argb = new BufferedImage(3, 2, BufferedImage.TYPE_4BYTE_ABGR);

		BufferedImage bgr = QualityNode.toBgr(argb);

		assertEquals(3 * 2 * 3, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData().length);
	}
}
