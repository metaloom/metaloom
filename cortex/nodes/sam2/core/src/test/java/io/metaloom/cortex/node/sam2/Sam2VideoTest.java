package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.cortex.node.sam2.video.SampledFrames;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The video paths of {@link Sam2Node}, with {@link Sam2FrameSampler} stubbed.
 *
 * <p>
 * Stubbing the sampler rather than opening a real file is deliberate: it keeps this test off the
 * OpenCV natives, and it lets the frame numbers be chosen — which is the whole point, because the
 * thing worth pinning is that <em>source</em> frame numbers survive the round trip rather than being
 * replaced by sequence indices.
 * </p>
 */
class Sam2VideoTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int NATIVE_W = 1920;
	private static final int NATIVE_H = 1080;
	private static final int SAMPLED_W = 1024;
	private static final int SAMPLED_H = 576;

	/** Every 25th frame, the default chop rate. */
	private static final List<Integer> FRAME_NUMBERS = List.of(0, 25, 50, 75);

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private Sam2Client sam2Client;
	private Sam2FrameSampler sampler;
	private StubLoomMedia media;
	private byte[] maskPng;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		maskPng = Sam2TestFixtures.binaryMaskPng(SAMPLED_W, SAMPLED_H, 10, 20, 30, 40);

		sam2Client = mock(Sam2Client.class);
		when(sam2Client.track(anyList(), anyList(), anyList(), any()))
			.thenReturn(Sam2TestFixtures.trackResponse(SAMPLED_W, SAMPLED_H, FRAME_NUMBERS, maskPng));

		sampler = mock(Sam2FrameSampler.class);
		when(sampler.sample(any(String.class), any())).thenReturn(frames(FRAME_NUMBERS, false));

		// A real (if tiny) file on disk so the existence check passes; the sampler never opens it.
		File videoFile = new File(tempDir, "clip.mp4");
		Files.writeString(videoFile.toPath(), "not really a video");

		media = new StubLoomMedia(videoFile.getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);
	}

	/** A sampled sequence of solid JPEG frames carrying the given source frame numbers. */
	private SampledFrames frames(List<Integer> frameNumbers, boolean capped) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(SAMPLED_W, SAMPLED_H, BufferedImage.TYPE_INT_RGB), "jpg", bos);
		String jpeg = Base64.getEncoder().encodeToString(bos.toByteArray());

		List<String> encoded = new ArrayList<>();
		for (int i = 0; i < frameNumbers.size(); i++) {
			encoded.add(jpeg);
		}
		return new SampledFrames(encoded, frameNumbers, NATIVE_W, NATIVE_H, SAMPLED_W, SAMPLED_H, capped);
	}

	private Sam2Node node(Sam2NodeOptions options) {
		return new Sam2Node(null, cortexOptions, options, sam2Client, sampler);
	}

	private NodeContext<LoomMedia> ctx(List<String> detections) {
		return NodeContext.create(media, NodeInputs.builder()
			.inputs(Sam2Node.IN_DETECTIONS, detections)
			.build());
	}

	private JsonObject manifestOf(NodeResult result) {
		return new JsonObject(result.get(Sam2Node.OUT_SEGMENTS));
	}

	/** A person detected on source frame 25, measured against the video's native frame. */
	private List<String> onePersonOnFrame25() {
		return List.of(Sam2TestFixtures.detectionElement(0, "person", 100, 200, 300, 400, NATIVE_W, NATIVE_H, 25));
	}

	@Test
	void testTrackKeepsSourceFrameNumbers() {
		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(ctx(onePersonOnFrame25()));
		assertThat(result).isSuccess();

		JsonArray masks = manifestOf(result).getJsonArray("masks");
		assertEquals(FRAME_NUMBERS.size(), masks.size());

		// 0, 25, 50, 75 - not 0, 1, 2, 3. A mask on sampled entry 3 means nothing; a mask on source
		// frame 75 can be seeked to.
		List<Integer> reported = new ArrayList<>();
		for (int i = 0; i < masks.size(); i++) {
			reported.add(masks.getJsonObject(i).getInteger("frame"));
		}
		assertEquals(FRAME_NUMBERS, reported);
	}

	@Test
	void testTrackMaskFileNamesCarryFrameAndObject() {
		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(ctx(onePersonOnFrame25()));
		assertThat(result).isSuccess();

		// A directory listing is then already an index: one object's track is a glob, one moment
		// across objects is a prefix.
		List<String> paths = result.elements(Sam2Node.OUT_MASKS);
		org.assertj.core.api.Assertions.assertThat(paths.get(0)).endsWith("f0000000-obj0001.png");
		org.assertj.core.api.Assertions.assertThat(paths.get(1)).endsWith("f0000025-obj0001.png");
		for (String path : paths) {
			assertTrue(Files.exists(Path.of(path)), "every tracked mask must be written: " + path);
		}
	}

	@Test
	void testTrackSnapsThePromptToTheNearestSampledFrame() {
		// The detector sampled at its own chop rate and stamped this box frame 60, which is not one of
		// this node's samples (0, 25, 50, 75). Dropping it would silently lose the object.
		node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK))
			.process(ctx(List.of(Sam2TestFixtures.detectionElement(0, "person", 100, 200, 300, 400, NATIVE_W, NATIVE_H, 60))));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2TrackPrompt>> prompts = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).track(anyList(), anyList(), prompts.capture(), any());

		// 60 is nearest to sampled entry 2 (source frame 50).
		assertEquals(1, prompts.getValue().size());
		assertEquals(2, prompts.getValue().get(0).frameIndex());
	}

	@Test
	void testTrackRescalesBoxesIntoTheSampledSpace() {
		node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(ctx(onePersonOnFrame25()));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2TrackPrompt>> prompts = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).track(anyList(), anyList(), prompts.capture(), any());

		// 1920 -> 1024 is a factor of 0.5333. The box was measured on the native frame; the sidecar
		// only ever sees the sampled one.
		double scale = (double) SAMPLED_W / NATIVE_W;
		Sam2Box box = prompts.getValue().get(0).box();
		assertEquals(100 * scale, box.x1(), 0.01d);
		assertEquals(200 * scale, box.y1(), 0.01d);
		assertEquals(400 * scale, box.x2(), 0.01d);
		assertEquals(600 * scale, box.y2(), 0.01d);
	}

	@Test
	void testTrackWithNoDetectionsPromptsTheWholeFrame() {
		// SAM 2's own demo shape: nothing wired means "everything here", which for a box-prompted
		// predictor is one box covering the frame.
		assertThat(node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(ctx(List.of()))).isSuccess();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2TrackPrompt>> prompts = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).track(anyList(), anyList(), prompts.capture(), any());

		Sam2Box box = prompts.getValue().get(0).box();
		assertEquals(0d, box.x1(), 0.001d);
		assertEquals(SAMPLED_W, box.x2(), 0.001d);
		assertEquals(SAMPLED_H, box.y2(), 0.001d);
	}

	@Test
	void testCappedFramesAreFlagged() throws Exception {
		when(sampler.sample(any(String.class), any())).thenReturn(frames(FRAME_NUMBERS, true));

		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK).setMaxFrames(4))
			.process(ctx(onePersonOnFrame25()));
		assertThat(result).isSuccess();

		// "The first N frames of the clip" is a different answer from "the clip".
		assertEquals(Sam2Node.FLAG_CAPPED, result.get(Sam2Node.OUT_FLAG));
		assertEquals(1, manifestOf(result).getJsonObject("truncated").getInteger("frames"));
	}

	@Test
	void testAutomaticOnVideoSegmentsTheAnnotationFrame() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, SAMPLED_W, SAMPLED_H, List.of(
				Sam2TestFixtures.mask(0, maskPng, 10, 20, 30, 40, 0.9d, null)), 0));

		// One frame rather than all of them: segment-everything over 64 frames would be tens of
		// thousands of mask files for one asset. TRACK is the mode that spans the clip.
		NodeResult result = node(new Sam2NodeOptions().setTrackFrame(2)).process(ctx(List.of()));
		assertThat(result).isSuccess();

		verify(sam2Client).segment(any(), any(), any(), any());
		verify(sam2Client, org.mockito.Mockito.never()).track(anyList(), anyList(), anyList(), any());
		assertEquals(50, manifestOf(result).getInteger("frame"), "the manifest names which source frame was segmented");
	}

	@Test
	void testPromptedOnVideoWithNoDetectionsIsSkipped() {
		assertThat(node(new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED)).process(ctx(List.of()))).isSkipped();
	}
}
