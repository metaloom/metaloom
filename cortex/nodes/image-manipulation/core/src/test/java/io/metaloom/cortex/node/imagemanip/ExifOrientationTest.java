package io.metaloom.cortex.node.imagemanip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the EXIF orientation off a file, including all the ways it can be absent.
 *
 * <p>
 * The absent cases matter more than the present ones. PNG carries no EXIF at all and is an entirely ordinary input to this node, so anything that made
 * a missing tag an error would break the commonest pipeline there is.
 * </p>
 */
class ExifOrientationTest {

	@TempDir
	File tempDir;

	@Test
	void testEveryExifValueIsReadBack() throws Exception {
		for (int exif = 1; exif <= 8; exif++) {
			File file = ImageManipFixtures.writeJpegWithOrientation(tempDir, "o" + exif + ".jpg",
				ImageManipFixtures.quadrants(40, 20), exif);
			assertEquals(exif, ExifOrientation.read(file).exifValue(), "orientation " + exif + " did not survive the round trip");
		}
	}

	@Test
	void testTheSplicedFixtureIsStillADecodableImage() throws Exception {
		// If this ever fails the orientation tests are asserting against a file no image node could read,
		// and the autorotate coverage would be worthless.
		File file = ImageManipFixtures.writeJpegWithOrientation(tempDir, "decodable.jpg", ImageManipFixtures.quadrants(40, 20), 6);
		assertEquals(40, ImageIO.read(file).getWidth());
	}

	@Test
	void testAFileWithNoExifIsNormalRatherThanAnError() throws Exception {
		File png = ImageManipFixtures.writePng(tempDir, "plain.png", ImageManipFixtures.quadrants(20, 20));
		assertEquals(Orientation.NORMAL, ExifOrientation.read(png));
	}

	@Test
	void testGarbageAndMissingFilesAreNormalRatherThanAnError() throws Exception {
		File notAnImage = new File(tempDir, "notes.txt");
		Files.writeString(notAnImage.toPath(), "this is not a photograph");
		assertEquals(Orientation.NORMAL, ExifOrientation.read(notAnImage));

		assertEquals(Orientation.NORMAL, ExifOrientation.read(new File(tempDir, "missing.jpg")));
		assertEquals(Orientation.NORMAL, ExifOrientation.read(null));
		assertEquals(Orientation.NORMAL, ExifOrientation.read(tempDir));
	}

	@Test
	void testATruncatedJpegDoesNotThrow() throws Exception {
		File file = ImageManipFixtures.writeJpegWithOrientation(tempDir, "cut.jpg", ImageManipFixtures.quadrants(40, 20), 3);
		byte[] bytes = Files.readAllBytes(file.toPath());
		File truncated = new File(tempDir, "truncated.jpg");
		Files.write(truncated.toPath(), java.util.Arrays.copyOf(bytes, 8));

		// Whatever it makes of eight bytes, it must be an orientation and not an exception.
		assertEquals(Orientation.NORMAL, ExifOrientation.read(truncated));
	}
}
