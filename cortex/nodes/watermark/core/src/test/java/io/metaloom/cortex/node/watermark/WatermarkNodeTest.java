package io.metaloom.cortex.node.watermark;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Unit test for {@link WatermarkNode}'s image path. Runs offline ({@code LoomClient == null}) and needs no ffmpeg - a still is composited entirely with
 * Graphics2D.
 *
 * <p>
 * The base and overlay are flat, contrasting colours ({@link WatermarkFixtures}), so "did the mark land in the right corner" is a pixel comparison rather
 * than a size check.
 * </p>
 */
class WatermarkNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int MEDIA_WIDTH = 400;
	private static final int MEDIA_HEIGHT = 200;

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private StubLoomMedia media;
	private String markBase64;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		File imageFile = new File(tempDir, "asset.png");
		ImageIO.write(WatermarkFixtures.baseImage(MEDIA_WIDTH, MEDIA_HEIGHT), "png", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);

		markBase64 = WatermarkFixtures.markBase64(40, 40);
	}

	private WatermarkNode node(WatermarkNodeOptions options) {
		return new WatermarkNode(null, cortexOptions, options);
	}

	private WatermarkNodeOptions options() {
		return new WatermarkNodeOptions().setWatermarkBase64(markBase64);
	}

	@Test
	void testWritesAWatermarkedPngIntoTheLocalCache() throws Exception {
		NodeResult result = node(options()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		String outPath = result.get(WatermarkNode.OUT_IMAGE);
		assertNotNull(outPath, "the node should emit the marked image path");
		assertEquals("DONE", result.get(WatermarkNode.OUT_FLAG));
		assertNull(result.get(WatermarkNode.OUT_VIDEO), "the video port must stay empty for an image item");

		Path png = Path.of(outPath);
		assertTrue(Files.exists(png), "the PNG should be written to the watermark_bin cache");
		assertTrue(png.startsWith(tempDir.toPath().resolve("watermark_bin")), "the PNG should live under metaPath/watermark_bin");

		BufferedImage marked = ImageIO.read(png.toFile());
		assertEquals(MEDIA_WIDTH, marked.getWidth(), "the source dimensions must be preserved");
		assertEquals(MEDIA_HEIGHT, marked.getHeight());
	}

	@Test
	void testTheSourceFileIsNeverModified() throws Exception {
		byte[] before = Files.readAllBytes(Path.of(media.absolutePath()));
		assertThat(node(options()).process(NodeContext.create(media))).isSuccess();
		byte[] after = Files.readAllBytes(Path.of(media.absolutePath()));
		assertEquals(before.length, after.length, "the node must not touch the original media");
		org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before);
	}

	@Test
	void testDefaultPlacementIsBottomRight() throws Exception {
		NodeResult result = node(options()).process(NodeContext.create(media));
		BufferedImage marked = ImageIO.read(Path.of(result.get(WatermarkNode.OUT_IMAGE)).toFile());

		// scale 0.2 of 400px = 80px square; relX/relY 0.95 -> x = (400-80)*0.95 = 304, y = (200-80)*0.95 = 114.
		assertEquals(WatermarkFixtures.MARK_COLOUR.getRGB(), marked.getRGB(340, 150) | 0xFF000000, "the mark should be in the bottom-right region");
		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), marked.getRGB(10, 10) | 0xFF000000, "the top-left should be untouched base pixels");
	}

	@Test
	void testPlacementFollowsTheRelativeOptions() throws Exception {
		NodeResult result = node(options().setRelX(0.0).setRelY(0.0)).process(NodeContext.create(media));
		BufferedImage marked = ImageIO.read(Path.of(result.get(WatermarkNode.OUT_IMAGE)).toFile());

		assertEquals(WatermarkFixtures.MARK_COLOUR.getRGB(), marked.getRGB(5, 5) | 0xFF000000, "relX/relY 0.0 should put the mark in the top-left");
		assertEquals(WatermarkFixtures.BASE_COLOUR.getRGB(), marked.getRGB(395, 195) | 0xFF000000, "the bottom-right should now be base pixels");
	}

	@Test
	void testSecondRunIsServedFromTheLocalCache() throws Exception {
		WatermarkNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();
		Path artifact = Path.of(first.get(WatermarkNode.OUT_IMAGE));

		// NodeResult carries no origin, so a cache hit is not directly observable. Overwrite the artifact with a sentinel instead: if the second run
		// re-composites it will replace these bytes, and if it is served from the cache they survive.
		Files.writeString(artifact, "sentinel");

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.get(WatermarkNode.OUT_IMAGE), second.get(WatermarkNode.OUT_IMAGE));
		assertEquals("sentinel", Files.readString(artifact), "the second run should not have re-composited the image");
	}

	@Test
	void testADeletedArtifactIsRecomputedRatherThanServedFromCache() throws Exception {
		WatermarkNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		Path artifact = Path.of(first.get(WatermarkNode.OUT_IMAGE));
		Files.delete(artifact);

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		// The cache must not hand downstream a path that no longer resolves, so the artifact has to be back and be a real PNG again.
		assertTrue(Files.exists(artifact));
		assertNotNull(ImageIO.read(artifact.toFile()), "the recomputed artifact should be a readable image");
	}

	@Test
	void testADifferentWatermarkGetsItsOwnArtifactPath() throws Exception {
		// Two watermark nodes in one graph must not serve each other's output. The options digest is part of the file name, not only of the cache key.
		String firstPath = node(options()).process(NodeContext.create(media)).get(WatermarkNode.OUT_IMAGE);
		String otherLogo = node(options().setWatermarkBase64(WatermarkFixtures.markBase64(24, 12)))
			.process(NodeContext.create(media)).get(WatermarkNode.OUT_IMAGE);
		String otherPlacement = node(options().setRelX(0.0)).process(NodeContext.create(media)).get(WatermarkNode.OUT_IMAGE);

		assertNotEquals(firstPath, otherLogo, "a different logo must land at a different path");
		assertNotEquals(firstPath, otherPlacement, "a different placement must land at a different path");
	}

	@Test
	void testAudioIsSkipped() {
		StubLoomMedia audio = new StubLoomMedia(media.absolutePath(), false, false, true, false);
		audio.setSHA512(HASH);
		assertThat(node(options()).process(NodeContext.create(audio))).isSkipped();
	}

	@Test
	void testDocumentIsSkipped() {
		StubLoomMedia doc = new StubLoomMedia(media.absolutePath(), false, false, false, true);
		doc.setSHA512(HASH);
		assertThat(node(options()).process(NodeContext.create(doc))).isSkipped();
	}

	@Test
	void testAnUndecodableWatermarkFailsRatherThanSucceedingWithNoArtifact() {
		// NodeContextImpl.next() ignores a recorded failure cause and would report SUCCESS, so the node must abort(). If this ever reports SUCCESS the
		// pipeline would treat an un-watermarked item as done.
		NodeResult result = node(options().setWatermarkBase64("!!! not base64 !!!")).process(NodeContext.create(media));
		assertThat(result).isFailed();
		assertEquals("FAILED", result.get(WatermarkNode.OUT_FLAG));
	}

	@Test
	void testADisabledNodeIsSkipped() {
		WatermarkNodeOptions disabled = options();
		disabled.setEnabled(false);
		assertThat(node(disabled).process(NodeContext.create(media))).isSkipped();
	}
}
