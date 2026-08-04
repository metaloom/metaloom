package io.metaloom.cortex.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Tests for the worker-side preview generator.
 *
 * <p>
 * The invariants that matter are the negative ones: a preview must never fail the node that
 * produced the real output, must never be truncated to fit, and must never be attempted for a value
 * that is not a local image file.
 * </p>
 */
public class NodePreviewsTest {

	@TempDir
	Path tempDir;

	/** A PNG of the given size, written to the temp directory. */
	private Path writeImage(String name, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.ORANGE);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		Path path = tempDir.resolve(name);
		ImageIO.write(image, "png", path.toFile());
		return path;
	}

	private static PortPayload payload(String contentType, Object value) {
		return new PortPayload(contentType, "ONE",
			List.of(new DataElement(new Origin("item-1", 0, 1), value)));
	}

	@Test
	@DisplayName("An artifact/image port yields a JPEG preview scaled to the edge cap")
	void testImagePortIsPreviewed() throws Exception {
		Path image = writeImage("big.png", 2000, 1000);

		Map<String, NodePreview> previews = NodePreviews.build(
			Map.of("thumbnail", payload(ContentTypeRegistry.ARTIFACT_IMAGE, image.toString())));

		assertThat(previews).containsKey("thumbnail");
		NodePreview preview = previews.get("thumbnail");
		assertThat(preview.hasData()).isTrue();
		assertThat(preview.getMimeType()).isEqualTo("image/jpeg");
		// Longest edge capped, aspect ratio kept.
		assertThat(preview.getWidth()).isEqualTo(NodePreview.MAX_EDGE_PX);
		assertThat(preview.getHeight()).isEqualTo(NodePreview.MAX_EDGE_PX / 2);
		assertThat(preview.getData().length).isLessThanOrEqualTo(NodePreview.DEFAULT_MAX_BYTES);
		// And it really is a decodable image, not just some bytes.
		assertThat(ImageIO.read(new ByteArrayInputStream(preview.getData()))).isNotNull();
	}

	@Test
	@DisplayName("An image already within the cap is not upscaled")
	void testSmallImageKeepsItsSize() throws Exception {
		Path image = writeImage("small.png", 64, 32);

		NodePreview preview = NodePreviews.build(
			Map.of("image", payload(ContentTypeRegistry.MEDIA_IMAGE, image.toString()))).get("image");

		assertThat(preview.getWidth()).isEqualTo(64);
		assertThat(preview.getHeight()).isEqualTo(32);
	}

	@Test
	@DisplayName("A non-image port is left alone")
	void testNonImagePortIsIgnored() throws Exception {
		// A hash is a string that is not a path, and a transcript is prose. Neither is
		// previewable, and neither should produce a skip entry either — there is nothing to
		// report about a port that was never a candidate.
		Map<String, NodePreview> previews = NodePreviews.build(Map.of(
			"sha512", payload(ContentTypeRegistry.HASH_SHA512, "0f8ef1c9"),
			"text", payload(ContentTypeRegistry.TEXT_PLAIN, "some words")));

		assertThat(previews).isEmpty();
	}

	@Test
	@DisplayName("An image port whose value is not a local file is left alone")
	void testMissingFileIsIgnored() {
		Map<String, NodePreview> previews = NodePreviews.build(
			Map.of("image", payload(ContentTypeRegistry.ARTIFACT_IMAGE, "/no/such/file.png")));

		assertThat(previews).isEmpty();
	}

	@Test
	@DisplayName("A URI value is left alone rather than treated as a path")
	void testUriValueIsIgnored() {
		// An s3:// value is a legitimate thing for a port to carry and is not reachable from
		// here. Path.of would either mangle it or throw depending on the platform.
		Map<String, NodePreview> previews = NodePreviews.build(
			Map.of("image", payload(ContentTypeRegistry.ARTIFACT_IMAGE, "s3://bucket/key.png")));

		assertThat(previews).isEmpty();
	}

	@Test
	@DisplayName("A readable file that is not an image is reported as skipped, not silently dropped")
	void testUnreadableImageIsSkipped() throws Exception {
		// The port declared it was an image, so its absence is worth explaining.
		Path notAnImage = tempDir.resolve("broken.png");
		Files.writeString(notAnImage, "this is not a PNG");

		NodePreview preview = NodePreviews.build(
			Map.of("image", payload(ContentTypeRegistry.ARTIFACT_IMAGE, notAnImage.toString()))).get("image");

		assertThat(preview).isNotNull();
		assertThat(preview.hasData()).isFalse();
		assertThat(preview.getSkippedReason()).isNotBlank();
	}

	@Test
	@DisplayName("An empty payload produces no preview")
	void testEmptyPayloadProducesNothing() {
		PortPayload empty = new PortPayload(ContentTypeRegistry.ARTIFACT_IMAGE, "ONE", List.of());
		assertThat(NodePreviews.build(Map.of("image", empty))).isEmpty();
		assertThat(NodePreviews.build(Map.of())).isEmpty();
		assertThat(NodePreviews.build(null)).isEmpty();
	}

	@Test
	@DisplayName("Scaling never rounds an edge down to zero")
	void testExtremeAspectRatioKeepsBothEdges() {
		// A 4000x3 panorama strip scaled to fit 512 would round its height to 0 and make the
		// BufferedImage constructor throw, failing a preview for an image that is perfectly fine.
		BufferedImage strip = new BufferedImage(4000, 3, BufferedImage.TYPE_INT_RGB);

		BufferedImage scaled = NodePreviews.scaleToFit(strip, NodePreview.MAX_EDGE_PX);

		assertThat(scaled.getWidth()).isEqualTo(NodePreview.MAX_EDGE_PX);
		assertThat(scaled.getHeight()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("A transparent source is flattened rather than failing the JPEG writer")
	void testAlphaSourceIsFlattened() throws Exception {
		BufferedImage withAlpha = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);
		Path path = tempDir.resolve("alpha.png");
		ImageIO.write(withAlpha, "png", path.toFile());

		NodePreview preview = NodePreviews.build(
			Map.of("image", payload(ContentTypeRegistry.ARTIFACT_IMAGE, path.toString()))).get("image");

		assertThat(preview.hasData()).isTrue();
		assertThat(ImageIO.read(new ByteArrayInputStream(preview.getData()))).isNotNull();
	}
}
