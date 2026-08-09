package io.metaloom.cortex.node.watermark;

import io.metaloom.cortex.fs.AtomicFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.node.watermark.WatermarkGeometry.Placement;

/**
 * Unit test for {@link WatermarkImages}: base64 decoding, compositing at exact coordinates, and atomic PNG writing.
 */
class WatermarkImagesTest {

	@TempDir
	File tempDir;

	@Test
	void testDecodesBareBase64() throws Exception {
		BufferedImage decoded = WatermarkImages.decode(WatermarkFixtures.markBase64(16, 8));
		assertEquals(16, decoded.getWidth());
		assertEquals(8, decoded.getHeight());
	}

	@Test
	void testDecodesFullDataUri() throws Exception {
		String uri = "data:image/png;base64," + WatermarkFixtures.markBase64(16, 8);
		BufferedImage decoded = WatermarkImages.decode(uri);
		assertEquals(16, decoded.getWidth());
		assertEquals(8, decoded.getHeight());
	}

	@Test
	void testDecodesPayloadContainingWhitespace() throws Exception {
		// A blob pasted into the editor's multi-line field arrives with line breaks, and one edited by hand can pick up spaces. Both must decode.
		String wrapped = WatermarkFixtures.markBase64(16, 8).replaceAll("(.{20})", "$1\n  ");
		BufferedImage decoded = WatermarkImages.decode(wrapped);
		assertEquals(16, decoded.getWidth());
	}

	@Test
	void testRejectsEmptyAndUndecodableInput() {
		assertThrows(IOException.class, () -> WatermarkImages.decode(null));
		assertThrows(IOException.class, () -> WatermarkImages.decode("   "));
		assertThrows(IOException.class, () -> WatermarkImages.decode("!!!not base64!!!"));
		// Valid base64 that is not an image at all.
		String notAnImage = Base64.getEncoder().encodeToString("hello".getBytes());
		assertThrows(IOException.class, () -> WatermarkImages.decode(notAnImage));
	}

	@Test
	void testCompositePlacesTheOverlayAtTheGivenCoordinates() {
		BufferedImage base = WatermarkFixtures.baseImage(100, 100);
		BufferedImage mark = WatermarkFixtures.markImage(10, 10);

		BufferedImage result = WatermarkImages.composite(base, mark, new Placement(80, 70, 10, 10), 1.0d);

		assertEquals(WatermarkFixtures.MARK_COLOUR.getRGB(), result.getRGB(85, 75) | 0xFF000000, "the overlay should cover its own rectangle");
		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), result.getRGB(5, 5) | 0xFF000000, "pixels outside the overlay should be untouched");
		// One pixel outside each edge of the placement rectangle must still be the base colour - an off-by-one in the placement would show up here.
		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), result.getRGB(79, 75) | 0xFF000000);
		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), result.getRGB(85, 69) | 0xFF000000);
	}

	@Test
	void testCompositeLeavesTheArgumentsUnmodified() {
		BufferedImage base = WatermarkFixtures.baseImage(50, 50);
		BufferedImage mark = WatermarkFixtures.markImage(10, 10);

		WatermarkImages.composite(base, mark, new Placement(0, 0, 10, 10), 1.0d);

		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), base.getRGB(5, 5) | 0xFF000000, "the source image must not be drawn onto in place");
	}

	@Test
	void testOpacityBlendsTowardsTheBase() {
		BufferedImage base = WatermarkFixtures.baseImage(20, 20);
		BufferedImage mark = WatermarkFixtures.markImage(20, 20);

		int opaque = WatermarkImages.composite(base, mark, new Placement(0, 0, 20, 20), 1.0d).getRGB(10, 10);
		int half = WatermarkImages.composite(base, mark, new Placement(0, 0, 20, 20), 0.5d).getRGB(10, 10);

		assertNotEquals(opaque, half, "a half-opacity overlay must not produce the same pixel as a full one");
		int halfRed = (half >> 16) & 0xFF;
		int baseRed = WatermarkFixtures.BASE_COLOUR.getRed();
		int markRed = WatermarkFixtures.MARK_COLOUR.getRed();
		assertTrue(halfRed > baseRed && halfRed < markRed,
			"a 50% blend of red " + baseRed + " and " + markRed + " should land between them but was " + halfRed);
	}

	@Test
	void testWritePngLeavesNoPartialFileBehind() throws Exception {
		Path target = tempDir.toPath().resolve("nested").resolve("out.png");
		WatermarkImages.writePng(WatermarkFixtures.baseImage(8, 8), target);

		assertTrue(Files.exists(target), "the PNG should be written, creating its parent directory");
		try (var entries = Files.list(target.getParent())) {
			assertEquals(1, entries.count(), "only the finished artifact should remain in the directory");
		}
		BufferedImage read = ImageIO.read(target.toFile());
		assertEquals(8, read.getWidth());
	}

	@Test
	void testPartFileKeepsTheTargetsExtensionLast() {
		// ffmpeg chooses its muxer from the file name's extension, so a temporary named "clip.mp4.part" fails with "Unable to choose an output format".
		// The marker therefore goes before the extension.
		assertEquals("clip.part.mp4", AtomicFiles.partFor(Path.of("/tmp/clip.mp4")).getFileName().toString());
		assertEquals("hash-0123.part.png", AtomicFiles.partFor(Path.of("/tmp/hash-0123.png")).getFileName().toString());
		assertEquals("noextension.part", AtomicFiles.partFor(Path.of("/tmp/noextension")).getFileName().toString());
	}

	@Test
	void testReadRejectsAFileThatIsNotAnImage() throws Exception {
		File notAnImage = new File(tempDir, "notes.txt");
		Files.writeString(notAnImage.toPath(), "definitely not a PNG");
		assertThrows(IOException.class, () -> WatermarkImages.read(notAnImage));
	}
}
