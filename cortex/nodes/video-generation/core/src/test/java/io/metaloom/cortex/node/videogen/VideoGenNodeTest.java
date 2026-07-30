package io.metaloom.cortex.node.videogen;

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
 * Deterministic unit test for {@link VideoGenNode}. The FastAPI sidecar is replaced by a mocked {@link VideoGenClient}, so no server is required.
 * Verifies the prompt-in / video-out flow: the MP4 is written under {@code metaPath/videogen_bin}, the output ports are emitted, both GENERATE and
 * ANIMATE modes call the right endpoint, and the node self-skips for non-image media.
 */
class VideoGenNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final byte[] FAKE_MP4 = "fake-video-bytes".getBytes();

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private VideoGenClient client;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		client = mock(VideoGenClient.class);
		when(client.generate(anyString(), nullable(String.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble(),
			nullable(Integer.class))).thenReturn(FAKE_MP4);
		when(client.animate(any(BufferedImage.class), anyString(), nullable(String.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
			anyDouble(), nullable(Integer.class))).thenReturn(FAKE_MP4);

		// Write a real image so ANIMATE's ImageIO.read succeeds; GENERATE ignores the pixels.
		File imageFile = new File(tempDir, "asset.png");
		ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private VideoGenNode node(VideoGenNodeOptions options) {
		return new VideoGenNode(null, cortexOptions, options, client);
	}

	private VideoGenNodeOptions options() {
		return new VideoGenNodeOptions().setPrompt("a glowing loom");
	}

	@Test
	void testGeneratesAndWritesMp4() throws Exception {
		NodeResult result = node(options()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		String outPath = result.get(VideoGenNode.OUT_VIDEO);
		assertNotNull(outPath, "The node should emit the generated video path");
		assertEquals("DONE", result.get(VideoGenNode.OUT_FLAG));

		Path mp4 = Path.of(outPath);
		assertTrue(Files.exists(mp4), "The MP4 file should be written to the videogen_bin cache");
		assertTrue(mp4.startsWith(tempDir.toPath().resolve("videogen_bin")), "The MP4 should live under metaPath/videogen_bin");
		assertEquals(FAKE_MP4.length, Files.size(mp4));

		verify(client).generate(eq("a glowing loom"), nullable(String.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble(),
			nullable(Integer.class));
	}

	@Test
	void testAnimateModeCallsAnimateEndpoint() throws Exception {
		NodeResult result = node(options().setMode(VideoGenMode.ANIMATE)).process(NodeContext.create(media));
		assertThat(result).isSuccess();
		assertEquals("DONE", result.get(VideoGenNode.OUT_FLAG));
		verify(client).animate(any(BufferedImage.class), eq("a glowing loom"), nullable(String.class), anyInt(), anyInt(), anyInt(), anyInt(),
			anyInt(), anyDouble(), nullable(Integer.class));
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
		VideoGenNode node = node(options());
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.get(VideoGenNode.OUT_VIDEO), second.get(VideoGenNode.OUT_VIDEO));

		// The sidecar must be hit exactly once - the second run is served from the in-heap cache.
		verify(client, times(1)).generate(anyString(), nullable(String.class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble(),
			nullable(Integer.class));
	}
}
