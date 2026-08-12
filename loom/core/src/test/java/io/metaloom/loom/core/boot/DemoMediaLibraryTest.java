package io.metaloom.loom.core.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.options.DemoOptions;

/**
 * The demo media loader, which decides what the demo container's asset browser shows.
 *
 * <p>
 * Worth a test of its own because every failure mode here is silent by design: {@code BootstrapInitializer} swallows whatever the demo seed throws
 * and never retries it, so a loader that answers null where it should answer bytes costs a picture and logs a line nobody reads.
 * </p>
 *
 * <p>
 * No database and no server: this is the one piece of the seeding that can be driven directly, unlike {@link DemoDatabaseInitializer#init()} which
 * bails on a non-empty asset table.
 * </p>
 */
public class DemoMediaLibraryTest {

	@TempDir
	Path root;

	/**
	 * The ordinary case: a photograph larger than the cap comes back resized, re-encoded and decodable.
	 */
	@Test
	public void testImageIsResizedToTheCap() throws IOException {
		writeImage("images/wide.jpg", 2560, 1707);

		byte[] bytes = new DemoMediaLibrary(root).image("images/wide.jpg");

		BufferedImage decoded = decode(bytes);
		assertEquals(DemoMediaLibrary.MAX_IMAGE_EDGE, decoded.getWidth(), "The long edge is capped");
		assertEquals(1067, decoded.getHeight(), "The aspect ratio is kept");
	}

	/**
	 * A picture already within the cap is not enlarged. The asset browser loads the stored binary itself, so growing a small file would cost bytes
	 * and buy nothing.
	 */
	@Test
	public void testSmallImageKeepsItsSize() throws IOException {
		writeImage("images/small.jpg", 800, 600);

		BufferedImage decoded = decode(new DemoMediaLibrary(root).image("images/small.jpg"));

		assertEquals(800, decoded.getWidth());
		assertEquals(600, decoded.getHeight());
	}

	/**
	 * Deterministic: the initializer stores what this returns under the hash of those bytes, so two runs over one file have to agree or a re-seeded
	 * container writes a second copy of every picture.
	 */
	@Test
	public void testTheSameFileEncodesToTheSameBytes() throws IOException {
		writeImage("images/wide.jpg", 2560, 1707);

		DemoMediaLibrary library = new DemoMediaLibrary(root);
		assertArrayEquals(library.image("images/wide.jpg"), library.image("images/wide.jpg"));
	}

	/**
	 * A portrait comes back square and at the size an avatar and a person's gallery need, whatever shape it went in as.
	 */
	@Test
	public void testPortraitIsSquareAndPortraitSized() throws IOException {
		writeImage("persons/face.jpg", 640, 640);

		BufferedImage decoded = decode(new DemoMediaLibrary(root).portrait("persons/face.jpg", 1.0));

		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decoded.getWidth());
		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decoded.getHeight());
	}

	/**
	 * The tighter framing is a different picture of the same file — which is the whole point of it, since it is what gives a person a gallery of two
	 * without a second photograph.
	 */
	@Test
	public void testCloseFramingDiffersFromTheWideOne() throws IOException {
		writeImage("persons/face.jpg", 640, 640);

		DemoMediaLibrary library = new DemoMediaLibrary(root);
		byte[] wide = library.portrait("persons/face.jpg", 1.0);
		byte[] close = library.portrait("persons/face.jpg", 0.72);

		assertFalse(java.util.Arrays.equals(wide, close), "A tighter crop must not encode to the same bytes as the whole frame");
		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decode(close).getWidth());
	}

	/**
	 * Geometry that does not fit degrades to a centred crop rather than throwing. The crops are recorded by hand in a README; a typo there must cost
	 * a good framing, not the picture.
	 */
	@Test
	public void testPortraitCropOutsideTheImageFallsBackToCentre() throws IOException {
		writeImage("users/original.jpg", 1000, 1000);

		byte[] bytes = new DemoMediaLibrary(root).portraitCrop("users/original.jpg", 900, 800, 800);

		assertNotNull(bytes, "An impossible crop still yields a portrait");
		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decode(bytes).getWidth());
	}

	/**
	 * A region crop takes the same normalised coordinates a detection box carries, and answers a square.
	 */
	@Test
	public void testRegionCropIsSquare() throws IOException {
		writeImage("images/group.jpg", 1920, 1280);

		byte[] bytes = new DemoMediaLibrary(root).regionCrop("images/group.jpg", 0.398, 0.325, 0.102, 0.225, 0.4);

		BufferedImage decoded = decode(bytes);
		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decoded.getWidth());
		assertEquals(DemoMediaLibrary.PORTRAIT_EDGE, decoded.getHeight());
	}

	/**
	 * No directory at all is the state every plain server is in, and it has to be quiet: the seed still runs, it just paints instead.
	 */
	@Test
	public void testAnAbsentLibraryAnswersNullThroughout() {
		DemoMediaLibrary library = new DemoMediaLibrary(null);

		assertFalse(library.isAvailable());
		assertNull(library.file("videos/clip.mp4"));
		assertNull(library.image("images/wide.jpg"));
		assertNull(library.portrait("persons/face.jpg", 1.0));
		assertNull(library.regionCrop("images/group.jpg", 0.1, 0.1, 0.2, 0.2, 0.4));
	}

	/**
	 * A directory that was configured but is not there is reported as unavailable rather than half-working.
	 */
	@Test
	public void testAMissingDirectoryIsUnavailable() {
		assertFalse(new DemoMediaLibrary(root.resolve("nope")).isAvailable());
	}

	/**
	 * One missing file costs that one entry. The seed has no second chance — a throw here would truncate everything after it — so the loader answers
	 * null and the caller skips.
	 */
	@Test
	public void testAMissingFileAnswersNullWithoutThrowing() throws IOException {
		writeImage("images/present.jpg", 400, 400);

		DemoMediaLibrary library = new DemoMediaLibrary(root);

		assertTrue(library.isAvailable());
		assertNull(library.image("images/absent.jpg"));
		assertNotNull(library.image("images/present.jpg"), "The rest of the directory still works");
	}

	/**
	 * A file that is not an image does not become one. Videos are handed to the caller as a path and never decoded.
	 */
	@Test
	public void testUndecodableBytesAnswerNull() throws IOException {
		Path path = root.resolve("images/broken.jpg");
		Files.createDirectories(path.getParent());
		Files.writeString(path, "this is not a JPEG");

		assertNull(new DemoMediaLibrary(root).image("images/broken.jpg"));
	}

	/**
	 * Videos are resolved, not read: the initializer hashes and copies them itself so an 11 MB clip never lands on a 512 MB heap.
	 */
	@Test
	public void testVideoIsResolvedAsAPath() throws IOException {
		Path clip = root.resolve("videos/clip.mp4");
		Files.createDirectories(clip.getParent());
		Files.write(clip, new byte[] { 0, 1, 2, 3 });

		assertEquals(clip, new DemoMediaLibrary(root).file("videos/clip.mp4"));
	}

	/**
	 * The probe order behind an unset {@code LOOM_DEMO_CONTENT_DIR}. An explicitly configured directory is handed back even when it does not exist,
	 * so a wrong path is a warning rather than a silent fall-back to painted pictures.
	 */
	@Test
	public void testConfiguredDirectoryWinsOverTheProbe() {
		DemoOptions options = new DemoOptions().setContentDirectory(root.resolve("nowhere").toString());

		assertEquals(root.resolve("nowhere"), options.resolveContentDirectory());
	}

	private void writeImage(String relativePath, int width, int height) throws IOException {
		Path path = root.resolve(relativePath);
		Files.createDirectories(path.getParent());
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		// Not a flat fill: a single colour survives any crop identically, so the close-framing test would
		// pass over a loader that ignored the zoom.
		for (int x = 0; x < width; x += 16) {
			g.setColor(new Color((x * 7) % 255, (x * 13) % 255, (x * 29) % 255));
			g.fillRect(x, 0, 16, height);
		}
		g.dispose();
		ImageIO.write(image, "jpeg", path.toFile());
	}

	private static BufferedImage decode(byte[] bytes) throws IOException {
		assertNotNull(bytes, "Expected image bytes");
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
		assertNotNull(decoded, "The stored bytes must decode as an image");
		return decoded;
	}

}
