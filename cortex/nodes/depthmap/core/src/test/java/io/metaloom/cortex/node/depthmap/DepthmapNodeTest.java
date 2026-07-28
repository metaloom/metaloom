package io.metaloom.cortex.node.depthmap;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link DepthmapNode}. The FastAPI sidecar is replaced by a mocked
 * {@link DepthmapClient}, so no GPU and no model download are required. Verifies the
 * image-in / map-out flow, the artifact layout, the convention guard, and the in-heap skip cache.
 */
class DepthmapNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int MAP_W = 64;
	private static final int MAP_H = 48;

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private DepthmapClient depthmapClient;
	private StubLoomMedia media;
	private byte[] png;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		png = DepthmapTestFixtures.gradientPng(MAP_W, MAP_H);

		depthmapClient = mock(DepthmapClient.class);
		when(depthmapClient.depth(any(), any(), any(), anyInt()))
			.thenReturn(DepthmapTestFixtures.response(png, MAP_W, MAP_H));

		// A real (tiny) JPEG on disk, because the node genuinely decodes and downscales it.
		File imageFile = new File(tempDir, "photo.jpg");
		ImageIO.write(new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);

		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private DepthmapNode node() {
		return node(new DepthmapNodeOptions());
	}

	private DepthmapNode node(DepthmapNodeOptions options) {
		return new DepthmapNode(null, cortexOptions, options, depthmapClient);
	}

	private NodeContext<LoomMedia> ctx() {
		return NodeContext.create(media);
	}

	@Test
	void testWritesMapAndEmitsOutputs() throws Exception {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		assertEquals("DONE", result.get(DepthmapNode.OUTPUT_DEPTHMAP_FLAG));

		String path = result.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH);
		assertNotNull(path, "The node should emit the artifact path");
		assertTrue(Files.exists(Path.of(path)), "The PNG must be written under metaPath/depthmap_bin");
		org.assertj.core.api.Assertions.assertThat(Files.readAllBytes(Path.of(path))).isEqualTo(png);

		// The artifact must land in the hash-segmented layout the other binary-producing nodes use.
		org.assertj.core.api.Assertions.assertThat(path)
			.contains("depthmap_bin")
			.endsWith(HASH + ".png");
	}

	@Test
	void testMetaCarriesBothDimensionPairs() throws Exception {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		JsonObject meta = new JsonObject(result.get(DepthmapNode.OUTPUT_DEPTHMAP_META));
		assertEquals(DepthmapTestFixtures.MODEL, meta.getString("model"));
		assertEquals("NEARNESS", meta.getString("convention"));
		assertEquals("RELATIVE", meta.getString("source"));

		// The map's own size, from the sidecar...
		assertEquals(MAP_W, meta.getInteger("width"));
		assertEquals(MAP_H, meta.getInteger("height"));
		// ...and the source image's, which is what lets a consumer project boxes onto the map.
		assertEquals(200, meta.getInteger("imageWidth"));
		assertEquals(150, meta.getInteger("imageHeight"));

		assertNotNull(meta.getJsonObject("stats"));
		assertEquals(result.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH), meta.getString("path"));
	}

	@Test
	void testProducedArtifactIsReadableAs16BitGrayscale() throws Exception {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		// The consumer contract: decode as USHORT_GRAY and read 0..65535, brighter = nearer.
		BufferedImage map = ImageIO.read(new File(result.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH)));
		assertEquals(BufferedImage.TYPE_USHORT_GRAY, map.getType());
		int left = map.getRaster().getSample(4, 4, 0);
		int right = map.getRaster().getSample(MAP_W - 4, 4, 0);
		assertTrue(left > right, "The near half must encode brighter than the far half");
		assertTrue(left > 50_000, "Nearness 0.9 should land near the top of the 16-bit range, was " + left);
	}

	@Test
	void testPassesOptionsToClient() throws Exception {
		DepthmapNodeOptions options = new DepthmapNodeOptions()
			.setMode(DepthMode.METRIC)
			.setModel("Intel/zoedepth-nyu-kitti")
			.setMaxDim(512);

		assertThat(node(options).process(ctx())).isSuccess();

		verify(depthmapClient).depth(any(), eq(DepthMode.METRIC), eq("Intel/zoedepth-nyu-kitti"), eq(512));
	}

	@Test
	void testModelOverrideAbsentByDefault() throws Exception {
		assertThat(node().process(ctx())).isSuccess();

		verify(depthmapClient).depth(any(), eq(DepthMode.RELATIVE), isNull(), eq(1024));
	}

	@Test
	void testImageIsDownscaledBeforeInference() throws Exception {
		// The source is 200x150; a maxDim of 100 must shrink the longest side to 100.
		assertThat(node(new DepthmapNodeOptions().setMaxDim(100)).process(ctx())).isSuccess();

		verify(depthmapClient).depth(org.mockito.ArgumentMatchers.argThat(
			(BufferedImage img) -> img.getWidth() == 100 && img.getHeight() == 75), any(), any(), anyInt());
	}

	@Test
	void testRejectsUnexpectedConvention() throws Exception {
		// A sidecar that answers in raw metres would invert every downstream ordering, so the node
		// refuses the result rather than storing something it cannot interpret.
		when(depthmapClient.depth(any(), any(), any(), anyInt()))
			.thenReturn(DepthmapTestFixtures.response(png, MAP_W, MAP_H).put("convention", "METRIC_METERS"));

		NodeResult result = node().process(ctx());

		assertEquals("FAILED", result.get(DepthmapNode.OUTPUT_DEPTHMAP_FLAG));
		assertNull(result.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH), "A rejected result must not emit an artifact path");
	}

	@Test
	void testSecondRunServedFromCacheWithoutReinference() throws Exception {
		DepthmapNode node = node();
		NodeResult first = node.process(ctx());
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctx());
		assertThat(second).isSuccess();
		assertEquals(first.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH), second.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH));
		assertEquals(first.get(DepthmapNode.OUTPUT_DEPTHMAP_META), second.get(DepthmapNode.OUTPUT_DEPTHMAP_META));

		verify(depthmapClient, times(1)).depth(any(), any(), any(), anyInt());
	}

	@Test
	void testCacheHitFallsThroughWhenArtifactWasDeleted() throws Exception {
		DepthmapNode node = node();
		NodeResult first = node.process(ctx());
		assertThat(first).isSuccess();

		// Someone cleared metaPath underneath us. The cached metadata is now a promise the
		// filesystem cannot keep, so the node must recompute rather than hand out a dead path.
		Files.delete(Path.of(first.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH)));

		assertThat(node.process(ctx())).isSuccess();
		verify(depthmapClient, times(2)).depth(any(), any(), any(), anyInt());
	}

	@Test
	void testFailureDoesNotPoisonCache() throws Exception {
		when(depthmapClient.depth(any(), any(), any(), anyInt()))
			.thenThrow(new RuntimeException("sidecar down"))
			.thenReturn(DepthmapTestFixtures.response(png, MAP_W, MAP_H));

		DepthmapNode node = node();
		NodeResult failed = node.process(ctx());
		assertEquals("FAILED", failed.get(DepthmapNode.OUTPUT_DEPTHMAP_FLAG));
		assertNull(failed.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH));

		NodeResult retry = node.process(ctx());
		assertThat(retry).isSuccess();
		assertNotNull(retry.get(DepthmapNode.OUTPUT_DEPTHMAP_PATH));
	}

	@Test
	void testNonImageIsSkipped() {
		StubLoomMedia video = new StubLoomMedia(media.absolutePath(), true, false, false, false);
		video.setSHA512(HASH);

		assertThat(node().process(NodeContext.create(video))).isSkipped();
	}

	@Test
	void testDisabledNodeIsSkipped() {
		DepthmapNodeOptions options = new DepthmapNodeOptions();
		options.setEnabled(false);

		assertThat(node(options).process(ctx())).isSkipped();
	}
}
