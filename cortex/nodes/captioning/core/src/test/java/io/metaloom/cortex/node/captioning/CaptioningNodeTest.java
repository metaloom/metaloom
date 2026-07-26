package io.metaloom.cortex.node.captioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.node.captioning.VideoCaptionOutput.SceneCaption;
import io.metaloom.video4j.Video4j;

/**
 * Unit-tests the merged {@link CaptioningNode} against stubbed vision-model clients, so no live SmolVLM / vLLM endpoint is needed. The image path is driven
 * with a {@link SmolVLMClient} whose {@code captionByImage} returns a canned caption; the video path (all three strategies) is driven with a
 * {@link VideoVLMClient} whose HTTP calls are stubbed. Frame decoding, scene segmentation and the node's own persistence path run for real (the Loom client
 * is null, so persistence is a no-op). The end-to-end write-back through REST is covered by {@code CaptioningNodeIntegrationTest}.
 */
public class CaptioningNodeTest extends AbstractMediaTest {

	static {
		Video4j.init();
	}

	private static final String IMAGE_CAPTION = "A mock caption describing the still image.";
	private static final String VIDEO_CAPTION = "A mock caption describing the video content.";

	/** A SmolVLMClient that returns a canned caption instead of calling a live endpoint, counting how often it is invoked. */
	private static SmolVLMClient stubImageClient(AtomicInteger calls) {
		return new SmolVLMClient("localhost", 0) {
			@Override
			public String captionByImage(BufferedImage image, int targetSize) {
				calls.incrementAndGet();
				return IMAGE_CAPTION;
			}
		};
	}

	/** A VideoVLMClient whose model round-trips are stubbed, so the real sampling / scene / native paths run without a server. */
	private static VideoVLMClient stubVideoClient() {
		return new VideoVLMClient("http://localhost:0", "mock-model", "", 256, 0.2d) {
			@Override
			public CaptionResult captionFrames(List<BufferedImage> frames, String prompt) {
				return new CaptionResult(VIDEO_CAPTION, 1, frames.size());
			}

			@Override
			public CaptionResult captionVideoUrl(String fileUri, String prompt) {
				return new CaptionResult(VIDEO_CAPTION, 1, 1);
			}
		};
	}

	private CaptioningNode node(CaptioningNodeOptions options) {
		return node(options, new AtomicInteger());
	}

	private CaptioningNode node(CaptioningNodeOptions options, AtomicInteger imageCalls) {
		// null Loom client = offline mode (no Loom server needed for unit tests).
		return new CaptioningNode(null, new CortexOptions(), options, stubImageClient(imageCalls), stubVideoClient());
	}

	@Test
	public void testCaptionsImage() throws IOException {
		CaptioningNode node = node(new CaptioningNodeOptions());
		NodeResult result = node.process(mediaImage1());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(IMAGE_CAPTION, result.get(CaptioningNode.OUTPUT_CAPTION));
	}

	@Test
	public void testImageSecondRunIsServedFromCache() throws IOException {
		AtomicInteger calls = new AtomicInteger();
		CaptioningNode node = node(new CaptioningNodeOptions(), calls);
		LoomMedia media = mediaImage1();

		NodeResult first = node.process(media);
		NodeResult second = node.process(media);

		assertEquals(ResultState.SUCCESS, first.getState());
		assertEquals(ResultState.SUCCESS, second.getState());
		assertEquals(IMAGE_CAPTION, second.get(CaptioningNode.OUTPUT_CAPTION));
		assertEquals(1, calls.get(), "The second run must be served from the local cache, not re-run the model");
	}

	@Test
	public void testCaptionsVideoWholeStrategy() throws IOException {
		CaptioningNodeOptions options = new CaptioningNodeOptions().setVideoStrategy(VideoCaptioningStrategy.WHOLE);
		NodeResult result = node(options).process(mediaVideo1());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(VIDEO_CAPTION, result.get(CaptioningNode.OUTPUT_CAPTION));
	}

	@Test
	public void testCaptionsVideoNativeStrategy() throws IOException {
		CaptioningNodeOptions options = new CaptioningNodeOptions().setVideoStrategy(VideoCaptioningStrategy.NATIVE);
		NodeResult result = node(options).process(mediaVideo1());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(VIDEO_CAPTION, result.get(CaptioningNode.OUTPUT_CAPTION));
	}

	@Test
	public void testCaptionsVideoSceneStrategy() throws Exception {
		CaptioningNodeOptions options = new CaptioningNodeOptions().setVideoStrategy(VideoCaptioningStrategy.SCENE);
		CaptioningNode node = node(options);

		// Assert the structured per-scene breakdown directly, since the node only emits the overall caption string.
		VideoCaptionOutput out = node.captionVideo(mediaVideo1());
		assertNotNull(out.caption());
		assertTrue(out.caption().contains(VIDEO_CAPTION), "The scene timeline must contain the per-scene captions: " + out.caption());
		assertTrue(out.scenes().size() >= 1, "At least one scene must be captioned");
		for (SceneCaption sc : out.scenes()) {
			assertEquals(VIDEO_CAPTION, sc.caption());
		}

		NodeResult result = node.process(mediaVideo1());
		assertEquals(ResultState.SUCCESS, result.getState());
	}

	@Test
	public void testSkipsAudio() throws IOException {
		NodeResult result = node(new CaptioningNodeOptions()).process(mediaAudio1());
		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	public void testSkipsDocument() throws IOException {
		NodeResult result = node(new CaptioningNodeOptions()).process(mediaDocDOCX());
		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	public void testDisabled() throws IOException {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setEnabled(false);
		NodeResult result = node(options).process(mediaImage1());
		assertEquals(ResultState.SKIPPED, result.getState());
	}
}
