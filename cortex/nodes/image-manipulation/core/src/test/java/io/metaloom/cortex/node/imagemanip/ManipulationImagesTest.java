package io.metaloom.cortex.node.imagemanip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Size;

/**
 * The pixel operations, asserted by reading individual pixels of a four-quadrant fixture.
 */
class ManipulationImagesTest {

	@TempDir
	File tempDir;

	private static Color at(BufferedImage image, double relX, double relY) {
		int x = Math.min(image.getWidth() - 1, (int) (image.getWidth() * relX));
		int y = Math.min(image.getHeight() - 1, (int) (image.getHeight() * relY));
		return new Color(image.getRGB(x, y), true);
	}

	// ── orientation ──────────────────────────────────────────────────────

	@Test
	void testAQuarterTurnMovesTheQuadrantsClockwise() {
		BufferedImage rotated = ManipulationImages.orient(ImageManipFixtures.quadrants(80, 40), Orientation.ROTATE_90);

		assertEquals(40, rotated.getWidth(), "a quarter turn must swap the axes");
		assertEquals(80, rotated.getHeight());
		// Bottom-left (blue) becomes top-left; top-left (red) becomes top-right.
		assertEquals(ImageManipFixtures.BOTTOM_LEFT.getRGB(), at(rotated, 0.25d, 0.25d).getRGB());
		assertEquals(ImageManipFixtures.TOP_LEFT.getRGB(), at(rotated, 0.75d, 0.25d).getRGB());
	}

	@Test
	void testAMirrorIsNotTheSameAsAHalfTurn() {
		// 🔴 The defect a rotation-only implementation ships: on a two-colour image these two are
		// indistinguishable, which is why the fixture has four.
		BufferedImage source = ImageManipFixtures.quadrants(80, 40);
		BufferedImage mirrored = ManipulationImages.orient(source, Orientation.MIRROR_HORIZONTAL);
		BufferedImage turned = ManipulationImages.orient(source, Orientation.ROTATE_180);

		assertEquals(ImageManipFixtures.TOP_RIGHT.getRGB(), at(mirrored, 0.25d, 0.25d).getRGB(),
			"a horizontal mirror should put the top-right quadrant on the left");
		assertEquals(ImageManipFixtures.BOTTOM_RIGHT.getRGB(), at(turned, 0.25d, 0.25d).getRGB(),
			"a half turn should put the bottom-right quadrant on the top left");
		assertNotEquals(at(mirrored, 0.25d, 0.25d).getRGB(), at(turned, 0.25d, 0.25d).getRGB());
	}

	@Test
	void testEveryMirroredOrientationDiffersFromItsRotationOnlyTwin() {
		BufferedImage source = ImageManipFixtures.quadrants(80, 40);
		// TRANSVERSE is "mirror + rotate 90"; plain ROTATE_90 is the rotation-only twin. Likewise
		// TRANSPOSE against ROTATE_270.
		assertNotEquals(
			at(ManipulationImages.orient(source, Orientation.TRANSVERSE), 0.25d, 0.25d).getRGB(),
			at(ManipulationImages.orient(source, Orientation.ROTATE_90), 0.25d, 0.25d).getRGB());
		assertNotEquals(
			at(ManipulationImages.orient(source, Orientation.TRANSPOSE), 0.25d, 0.25d).getRGB(),
			at(ManipulationImages.orient(source, Orientation.ROTATE_270), 0.25d, 0.25d).getRGB());
	}

	@Test
	void testTheIdentityOrientationHandsBackTheSameImage() {
		BufferedImage source = ImageManipFixtures.quadrants(80, 40);
		assertTrue(source == ManipulationImages.orient(source, Orientation.NORMAL), "the identity should not copy");
	}

	// ── crop ─────────────────────────────────────────────────────────────

	@Test
	void testCropTakesTheRequestedWindow() {
		BufferedImage cropped = ManipulationImages.crop(ImageManipFixtures.quadrants(80, 40), new Rect(0, 0, 40, 20));
		assertEquals(40, cropped.getWidth());
		assertEquals(20, cropped.getHeight());
		assertEquals(ImageManipFixtures.TOP_LEFT.getRGB(), at(cropped, 0.5d, 0.5d).getRGB());
	}

	@Test
	void testCropNeverWritesIntoTheSourceRaster() {
		// 🔴 The source comes from MediaArtifacts.decodedImage and is shared with every other node in the
		// segment. getSubimage would return a view onto it, and the next operation would corrupt it.
		BufferedImage source = ImageManipFixtures.quadrants(80, 40);
		int before = source.getRGB(0, 0);

		BufferedImage cropped = ManipulationImages.crop(source, new Rect(0, 0, 40, 20));
		cropped.setRGB(0, 0, Color.MAGENTA.getRGB());

		assertEquals(before, source.getRGB(0, 0), "writing to the crop changed the shared source image");
	}

	// ── padding ──────────────────────────────────────────────────────────

	@Test
	void testColourPaddingPutsTheRequestedColourInTheMargins() {
		BufferedImage source = ImageManipFixtures.quadrants(40, 80);
		BufferedImage padded = ManipulationImages.padWithColor(source, new Size(160, 80), Color.MAGENTA.getRGB() | 0xFF000000);

		assertEquals(160, padded.getWidth());
		assertEquals(Color.MAGENTA.getRGB(), at(padded, 0.02d, 0.5d).getRGB(), "the left margin should be the pad colour");
		assertEquals(Color.MAGENTA.getRGB(), at(padded, 0.98d, 0.5d).getRGB(), "the right margin should be the pad colour");
	}

	@Test
	void testBlurPaddingFillsTheMarginsWithThePictureRatherThanBars() {
		// The vertical-video fix: a portrait frame padded to landscape must have margins that come from
		// the image, so neither black nor the pad colour.
		BufferedImage source = ImageManipFixtures.quadrants(40, 80);
		BufferedImage padded = ManipulationImages.padWithBlur(source, new Size(160, 80), 24, 1.15d);

		assertEquals(160, padded.getWidth());
		assertEquals(80, padded.getHeight());

		Color margin = at(padded, 0.02d, 0.5d);
		assertNotEquals(Color.BLACK.getRGB(), margin.getRGB(), "the margin is a black bar, not a blurred backdrop");
		assertEquals(255, margin.getAlpha(), "the margin must be opaque");
	}

	@Test
	void testTheBlurredBackdropCoversEveryMarginPixel() {
		// At exactly cover size the backdrop's own edges land on the canvas edges and rounding leaves a
		// transparent sliver. blurZoom exists to prevent that; this is what would catch its removal.
		BufferedImage padded = ManipulationImages.padWithBlur(ImageManipFixtures.quadrants(41, 83), new Size(200, 83), 24, 1.15d);
		for (int x = 0; x < padded.getWidth(); x++) {
			for (int y = 0; y < padded.getHeight(); y += 7) {
				assertEquals(255, new Color(padded.getRGB(x, y), true).getAlpha(),
					"a transparent gap at " + x + "," + y + " - the backdrop did not cover the canvas");
			}
		}
	}

	@Test
	void testPaddingKeepsTheOriginalPixelsInTheMiddle() {
		BufferedImage padded = ManipulationImages.padWithBlur(ImageManipFixtures.quadrants(40, 80), new Size(160, 80), 24, 1.15d);
		// The source is centred, so its own top-left quadrant sits just right of centre-left.
		assertEquals(ImageManipFixtures.TOP_LEFT.getRGB(), at(padded, 0.40d, 0.25d).getRGB());
		assertEquals(ImageManipFixtures.BOTTOM_RIGHT.getRGB(), at(padded, 0.60d, 0.75d).getRGB());
	}

	// ── resize ───────────────────────────────────────────────────────────

	@Test
	void testResizeProducesTheExactRequestedSize() {
		BufferedImage resized = ManipulationImages.resize(ImageManipFixtures.quadrants(80, 40), new Size(20, 10));
		assertEquals(20, resized.getWidth());
		assertEquals(10, resized.getHeight());
	}

	// ── encoding ─────────────────────────────────────────────────────────

	@Test
	void testWritingJpegFlattensAlphaInsteadOfEmittingInvertedColour() throws Exception {
		// 🔴 ImageIO does not reject a TYPE_INT_ARGB raster for JPEG - it writes four components into a
		// three-component format and the result reads back inverted or magenta.
		Path target = tempDir.toPath().resolve("flat.jpg");
		ManipulationImages.write(ImageManipFixtures.halfTransparent(40, 20), target, OutputFormat.JPEG, 0.9d, 0xFFFFFFFF);

		BufferedImage read = ImageIO.read(target.toFile());
		assertNotNull(read, "the JPEG could not be decoded");
		assertFalse(read.getColorModel().hasAlpha(), "a JPEG must not claim an alpha channel");

		Color background = at(read, 0.1d, 0.5d);
		assertTrue(background.getRed() > 200 && background.getGreen() > 200 && background.getBlue() > 200,
			"the transparent half should have been flattened onto white, got " + background);
		Color painted = at(read, 0.9d, 0.5d);
		assertTrue(painted.getRed() > 200 && painted.getGreen() < 80, "the opaque half should still be red, got " + painted);
	}

	@Test
	void testWritingPngKeepsTransparency() throws Exception {
		Path target = tempDir.toPath().resolve("keep.png");
		ManipulationImages.write(ImageManipFixtures.halfTransparent(40, 20), target, OutputFormat.PNG, 0.9d, 0xFFFFFFFF);

		BufferedImage read = ImageIO.read(target.toFile());
		assertTrue(read.getColorModel().hasAlpha(), "PNG should have kept the alpha channel");
		assertEquals(0, at(read, 0.1d, 0.5d).getAlpha(), "the transparent half should still be transparent");
	}

	@Test
	void testWritingLeavesNoPartFileBehind() throws Exception {
		Path target = tempDir.toPath().resolve("nested").resolve("clean.jpg");
		ManipulationImages.write(ImageManipFixtures.quadrants(40, 20), target, OutputFormat.JPEG, 0.9d, 0xFFFFFFFF);

		assertTrue(Files.exists(target), "the artifact was not published");
		try (var entries = Files.list(target.getParent())) {
			assertEquals(1, entries.count(), "a .part file survived the write");
		}
	}

	@Test
	void testThePartFileKeepsTheTargetsExtensionLast() {
		// ImageIO does not care, but the watermark node's ffmpeg path does - one rule across the tree.
		assertEquals("photo.part.jpg", AtomicFiles.partFor(Path.of("/tmp/photo.jpg")).getFileName().toString());
		assertEquals("noext.part", AtomicFiles.partFor(Path.of("/tmp/noext")).getFileName().toString());
	}

	@Test
	void testJpegQualityChangesTheEncodedSize() throws Exception {
		// A quality knob that silently did nothing would be invisible otherwise.
		BufferedImage image = ImageManipFixtures.quadrants(200, 200);
		Path low = tempDir.toPath().resolve("low.jpg");
		Path high = tempDir.toPath().resolve("high.jpg");
		ManipulationImages.write(image, low, OutputFormat.JPEG, 0.1d, 0xFFFFFFFF);
		ManipulationImages.write(image, high, OutputFormat.JPEG, 1.0d, 0xFFFFFFFF);

		assertTrue(Files.size(low) < Files.size(high),
			"quality 0.1 produced " + Files.size(low) + " bytes and quality 1.0 " + Files.size(high));
	}

	@Test
	void testTheBlurActuallyBlurs() {
		BufferedImage blurred = ManipulationImages.boxBlur(ImageManipFixtures.quadrants(80, 80), 8);
		// On the quadrant seam the colours must have bled into each other.
		Color seam = new Color(blurred.getRGB(40, 40), true);
		assertNotEquals(ImageManipFixtures.TOP_LEFT.getRGB(), seam.getRGB());
		assertNotEquals(ImageManipFixtures.BOTTOM_RIGHT.getRGB(), seam.getRGB());
	}
}
