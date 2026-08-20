package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Deterministic unit test for {@link ImageGenNode}. The FastAPI sidecar is replaced by a mocked {@link ImageGenClient}, so no server is required.
 * Verifies the prompt-in / image-out flow: the PNG is written under {@code metaPath/imagegen_bin}, the output keys are emitted, both GENERATE and
 * REMIX modes call the right endpoint, and the node self-skips for non-image media.
 */
class ImageGenNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final byte[] FAKE_PNG = "fake-image-bytes".getBytes();

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private ImageGenClient client;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		client = mock(ImageGenClient.class);
		when(client.generate(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyInt())).thenReturn(FAKE_PNG);
		when(client.remix(any(BufferedImage.class), anyString(), anyDouble(), nullable(Integer.class), anyInt())).thenReturn(FAKE_PNG);

		// Write a real image so REMIX's ImageIO.read succeeds; GENERATE ignores the pixels.
		File imageFile = new File(tempDir, "asset.png");
		ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private ImageGenNode node(ImageGenNodeOptions options) {
		return new ImageGenNode(null, cortexOptions, options, client);
	}

	private ImageGenNodeOptions options() {
		return new ImageGenNodeOptions().setPrompt("a red apple");
	}

	@Test
	void testGeneratesAndWritesPng() throws Exception {
		NodeResult result = node(options()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		String outPath = result.get(ImageGenNode.OUT_IMAGE);
		assertNotNull(outPath, "The node should emit the generated image path");
		assertEquals("DONE", result.get(ImageGenNode.OUT_FLAG));

		Path png = Path.of(outPath);
		assertTrue(Files.exists(png), "The PNG file should be written to the imagegen_bin cache");
		assertTrue(png.startsWith(tempDir.toPath().resolve("imagegen_bin")), "The PNG should live under metaPath/imagegen_bin");
		assertEquals(FAKE_PNG.length, Files.size(png));

		verify(client).generate(eq("a red apple"), anyInt(), anyInt(), nullable(Integer.class), anyInt());
	}

	@Test
	void testRemixModeCallsRemixEndpoint() throws Exception {
		NodeResult result = node(options().setMode(ImageGenMode.REMIX)).process(NodeContext.create(media));
		assertThat(result).isSuccess();
		assertEquals("DONE", result.get(ImageGenNode.OUT_FLAG));
		verify(client).remix(any(BufferedImage.class), eq("a red apple"), anyDouble(), nullable(Integer.class), anyInt());
	}

	@Test
	void testSkippedForNonImage() {
		StubLoomMedia video = new StubLoomMedia(media.absolutePath(), true, false, false, false);
		video.setSHA512(HASH);
		NodeResult result = node(options()).process(NodeContext.create(video));
		assertThat(result).isSkipped();
	}

	@Test
	void testSecondRunServedFromCache() throws Exception {
		ImageGenNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.get(ImageGenNode.OUT_IMAGE), second.get(ImageGenNode.OUT_IMAGE));

		// The sidecar must be hit exactly once - the second run is served from the in-heap cache.
		verify(client, times(1)).generate(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyInt());
	}

	/**
	 * The cache hit must be visible as provenance: real work reports {@code COMPUTED}, the replay
	 * reports {@code LOCAL} — the distinction the ledger records since the origin stopped being a
	 * constant.
	 */
	@Test
	void testCacheHitReportsLocalOrigin() {
		ImageGenNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertEquals(io.metaloom.cortex.api.node.ResultOrigin.COMPUTED, first.getOrigin());

		NodeResult second = node.process(NodeContext.create(media));
		assertEquals(io.metaloom.cortex.api.node.ResultOrigin.LOCAL, second.getOrigin());
	}

	/**
	 * Two instances in one graph differing only in their configuration — the obvious way to render
	 * two prompts — must write two distinct files and carry two distinct ledger ids. Before the
	 * options digest they wrote to the same {@code <sha512>.png} path and served each other's
	 * cached result.
	 */
	@Test
	void testTwoInstancesDifferingOnlyByOptionsWriteDistinctFiles() throws Exception {
		ImageGenNode first = node(options());
		first.configure(new io.vertx.core.json.JsonObject().put("id", "gen-apple").put("prompt", "a red apple"));
		ImageGenNode second = node(options());
		second.configure(new io.vertx.core.json.JsonObject().put("id", "gen-pear").put("prompt", "a blue pear"));

		NodeResult firstResult = first.process(NodeContext.create(media));
		NodeResult secondResult = second.process(NodeContext.create(media));
		assertThat(firstResult).isSuccess();
		assertThat(secondResult).isSuccess();

		String firstPath = firstResult.get(ImageGenNode.OUT_IMAGE);
		String secondPath = secondResult.get(ImageGenNode.OUT_IMAGE);
		assertTrue(!firstPath.equals(secondPath), "Two differently configured instances must not share an output path");
		assertTrue(Files.exists(Path.of(firstPath)));
		assertTrue(Files.exists(Path.of(secondPath)));

		// Each instance renders its own prompt - neither may be served the other's cached result.
		verify(client).generate(eq("a red apple"), anyInt(), anyInt(), nullable(Integer.class), anyInt());
		verify(client).generate(eq("a blue pear"), anyInt(), anyInt(), nullable(Integer.class), anyInt());

		// The ledger identity is the graph-local id, so the two rows upsert side by side instead of
		// overwriting each other on (asset_uuid, node_kind, node_id).
		assertEquals("gen-apple", first.nodeId());
		assertEquals("gen-pear", second.nodeId());
	}
}
