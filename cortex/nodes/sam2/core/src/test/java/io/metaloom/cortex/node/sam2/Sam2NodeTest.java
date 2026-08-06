package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link Sam2Node} on still images. The FastAPI sidecar is replaced by a
 * mocked {@link Sam2Client}, so no GPU and no model download are required, and nothing here touches
 * video4j's natives.
 *
 * <p>
 * Covers the artifact layout, the manifest contract, both prompt paths, the skip and failure paths,
 * and the two cache invariants that keep two configurations of this node from serving each other's
 * masks.
 * </p>
 */
class Sam2NodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	/** The source image on disk, and therefore the space upstream boxes are measured in. */
	private static final int SOURCE_W = 200;
	private static final int SOURCE_H = 150;

	/** The masks come back at the posted size, which for the default maxDim is the source size. */
	private static final int MASK_W = SOURCE_W;
	private static final int MASK_H = SOURCE_H;

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private Sam2Client sam2Client;
	private StubLoomMedia media;
	private byte[] maskPng;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		maskPng = Sam2TestFixtures.binaryMaskPng(MASK_W, MASK_H, 10, 20, 30, 40);

		sam2Client = mock(Sam2Client.class);
		when(sam2Client.segment(any(), any(), any(), any())).thenReturn(twoMasks());

		// A real (tiny) JPEG on disk, because the node genuinely decodes and downscales it.
		File imageFile = new File(tempDir, "photo.jpg");
		ImageIO.write(new BufferedImage(SOURCE_W, SOURCE_H, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);

		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private JsonObject twoMasks() throws Exception {
		return Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, MASK_W, MASK_H, List.of(
			Sam2TestFixtures.mask(0, maskPng, 10, 20, 30, 40, 0.93d, null),
			Sam2TestFixtures.mask(1, maskPng, 60, 20, 30, 40, 0.81d, null)), 0);
	}

	private Sam2Node node() {
		return node(new Sam2NodeOptions());
	}

	private Sam2Node node(Sam2NodeOptions options) {
		return new Sam2Node(null, cortexOptions, options, sam2Client, new Sam2FrameSampler());
	}

	private NodeContext<LoomMedia> ctx() {
		return NodeContext.create(media);
	}

	private NodeContext<LoomMedia> ctx(List<String> detections) {
		return NodeContext.create(media, NodeInputs.builder()
			.inputs(Sam2Node.IN_DETECTIONS, detections)
			.build());
	}

	private JsonObject manifestOf(NodeResult result) {
		String json = result.get(Sam2Node.OUT_SEGMENTS);
		assertNotNull(json, "the node must emit the segmentation manifest");
		return new JsonObject(json);
	}

	// ── AUTOMATIC ──────────────────────────────────────────────────────────

	@Test
	void testAutomaticWritesMasksAndEmitsPorts() {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		assertEquals(Sam2Node.FLAG_SUCCESS, result.get(Sam2Node.OUT_FLAG));
		assertEquals(2L, result.get(Sam2Node.OUT_MASK_COUNT));
		assertThat(result).hasElementCount(Sam2Node.OUT_MASKS, 2);

		for (String path : result.elements(Sam2Node.OUT_MASKS)) {
			assertTrue(Files.exists(Path.of(path)), "every emitted mask path must exist: " + path);
			// The hash-segmented layout every binary-producing node uses, plus the digest that keeps
			// two configurations apart.
			org.assertj.core.api.Assertions.assertThat(path)
				.contains("sam2_bin")
				.contains(HASH.toString() + "-");
		}
	}

	@Test
	void testProducedMaskIsBinaryGrayscale() throws Exception {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		// The consumer contract: decode as TYPE_BYTE_GRAY, 255 inside and 0 outside.
		BufferedImage mask = ImageIO.read(new File(result.elements(Sam2Node.OUT_MASKS).get(0)));
		assertEquals(BufferedImage.TYPE_BYTE_GRAY, mask.getType());
		assertEquals(255, mask.getRaster().getSample(15, 25, 0), "inside the mask must be set");
		assertEquals(0, mask.getRaster().getSample(150, 130, 0), "outside the mask must be clear");
	}

	@Test
	void testManifestCarriesBothDimensionPairs() {
		JsonObject manifest = manifestOf(node().process(ctx()));

		assertEquals(Sam2TestFixtures.MODEL, manifest.getString("model"));
		assertEquals("AUTOMATIC", manifest.getString("mode"));
		// The masks' own size, from the sidecar...
		assertEquals(MASK_W, manifest.getInteger("width"));
		assertEquals(MASK_H, manifest.getInteger("height"));
		// ...and the source image's, which is what lets a consumer project a mask back onto it.
		assertEquals(SOURCE_W, manifest.getInteger("imageWidth"));
		assertEquals(SOURCE_H, manifest.getInteger("imageHeight"));
	}

	@Test
	void testManifestIsWrittenLastAndNamesEveryMask() {
		NodeResult result = node().process(ctx());
		JsonObject manifest = manifestOf(result);

		Path manifestFile = Path.of(manifest.getString("dir")).resolve(Sam2Node.MANIFEST_NAME);
		assertTrue(Files.exists(manifestFile), "the manifest commits the directory and must be on disk");

		JsonArray masks = manifest.getJsonArray("masks");
		assertEquals(2, masks.size());
		assertEquals(result.elements(Sam2Node.OUT_MASKS),
			List.of(masks.getJsonObject(0).getString("path"), masks.getJsonObject(1).getString("path")),
			"the manifest must name exactly the paths the masks port emitted, in order");
	}

	@Test
	void testOverlayIsWrittenWhenEnabledAndAbsentWhenNot() {
		String overlay = node().process(ctx()).get(Sam2Node.OUT_OVERLAY);
		assertNotNull(overlay, "the overlay is the only honest whole-result preview and defaults on");
		assertTrue(Files.exists(Path.of(overlay)));

		assertNull(node(new Sam2NodeOptions().setEmitOverlay(false)).process(ctx()).get(Sam2Node.OUT_OVERLAY));
	}

	@Test
	void testPassesOptionsToClient() {
		Sam2NodeOptions options = new Sam2NodeOptions()
			.setModel("facebook/sam2.1-hiera-large")
			.setMaxDim(100)
			.setPointsPerSide(16);

		assertThat(node(options).process(ctx())).isSuccess();

		verify(sam2Client).segment(any(), eq(Sam2Mode.AUTOMATIC), any(), eq(options));
	}

	@Test
	void testImageIsDownscaledBeforeInference() throws Exception {
		// The source is 200x150; a maxDim of 100 must shrink the longest side to 100.
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, 100, 75, List.of(
				Sam2TestFixtures.mask(0, Sam2TestFixtures.binaryMaskPng(100, 75, 1, 1, 10, 10), 1, 1, 10, 10, 0.9d, null)), 0));

		JsonObject manifest = manifestOf(node(new Sam2NodeOptions().setMaxDim(100)).process(ctx()));

		ArgumentCaptor<String> posted = ArgumentCaptor.forClass(String.class);
		verify(sam2Client).segment(posted.capture(), any(), any(), any());
		BufferedImage sent = Sam2Images.decodeBase64(posted.getValue());
		assertEquals(100, sent.getWidth());
		assertEquals(75, sent.getHeight());

		// Both spaces stay named even when they differ, which is the case the pairs exist for.
		assertEquals(100, manifest.getInteger("width"));
		assertEquals(SOURCE_W, manifest.getInteger("imageWidth"));
	}

	@Test
	void testMaskDimensionsComeFromTheSidecarNotThePostedImage() throws Exception {
		// The sidecar clamps max_dim to its own SAM2_MAX_DIM, so asking for more than it allows gets
		// masks smaller than the image that was sent. Reporting the posted size here would hand every
		// consumer a scale factor that is quietly wrong.
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, 64, 48, List.of(
				Sam2TestFixtures.mask(0, Sam2TestFixtures.binaryMaskPng(64, 48, 2, 2, 10, 10), 2, 2, 10, 10, 0.9d, null)), 0));

		JsonObject manifest = manifestOf(node().process(ctx()));

		assertEquals(64, manifest.getInteger("width"), "the mask space is whatever the sidecar says it is");
		assertEquals(48, manifest.getInteger("height"));
		assertEquals(SOURCE_W, manifest.getInteger("imageWidth"), "...and the source space is still named alongside it");
		assertEquals(SOURCE_H, manifest.getInteger("imageHeight"));
	}

	@Test
	void testOverlayIsWrittenWhenMasksAreSmallerThanTheFrame() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, 64, 48, List.of(
				Sam2TestFixtures.mask(0, Sam2TestFixtures.binaryMaskPng(64, 48, 2, 2, 10, 10), 2, 2, 10, 10, 0.9d, null)), 0));

		String overlay = node().process(ctx()).get(Sam2Node.OUT_OVERLAY);
		assertNotNull(overlay);

		// Stretched to the frame, not drawn at the origin - a mismatch drawn 1:1 would pile every mask
		// into the top-left corner and read as a segmentation failure.
		BufferedImage composited = ImageIO.read(new File(overlay));
		assertEquals(SOURCE_W, composited.getWidth());
		assertEquals(SOURCE_H, composited.getHeight());
	}

	@Test
	void testEmptyResultIsNoneNotFailure() {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, MASK_W, MASK_H, List.of(), 0));

		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();
		// A blank wall is a valid answer.
		assertEquals(Sam2Node.FLAG_NONE, result.get(Sam2Node.OUT_FLAG));
		assertEquals(0L, result.get(Sam2Node.OUT_MASK_COUNT));
	}

	@Test
	void testCappedFlagWhenTheSidecarTruncated() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, MASK_W, MASK_H, List.of(
				Sam2TestFixtures.mask(0, maskPng, 10, 20, 30, 40, 0.93d, null)), 6));

		NodeResult result = node(new Sam2NodeOptions().setMaxMasks(1)).process(ctx());
		assertThat(result).isSuccess();

		// "The first N of what is in the file" is a different answer from "what is in the file".
		assertEquals(Sam2Node.FLAG_CAPPED, result.get(Sam2Node.OUT_FLAG));
		assertEquals(6, manifestOf(result).getJsonObject("truncated").getInteger("masks"));
	}

	// ── PROMPTED ───────────────────────────────────────────────────────────

	@Test
	void testPromptedSegmentsTheWiredBoxes() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.PROMPTED, MASK_W, MASK_H, List.of(
				Sam2TestFixtures.mask(0, maskPng, 10, 20, 30, 40, 0.93d, "person")), 0));

		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED))
			.process(ctx(List.of(Sam2TestFixtures.detectionElement(0, "person", 10, 20, 30, 40, SOURCE_W, SOURCE_H, 0))));
		assertThat(result).isSuccess();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2Box>> boxes = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).segment(any(), eq(Sam2Mode.PROMPTED), boxes.capture(), any());

		// The upstream element is XYWH; SAM 2 takes XYXY. Nothing was downscaled here, so the numbers
		// are the source pixels themselves.
		assertEquals(1, boxes.getValue().size());
		Sam2Box box = boxes.getValue().get(0);
		assertEquals(10d, box.x1(), 0.001d);
		assertEquals(20d, box.y1(), 0.001d);
		assertEquals(40d, box.x2(), 0.001d, "x2 must be x + w, not w");
		assertEquals(60d, box.y2(), 0.001d, "y2 must be y + h, not h");
		assertEquals("person", box.label());

		// The upstream label rides along onto the mask, which is the only thing making the result
		// searchable by what it is rather than only by where it is.
		assertEquals("person", manifestOf(result).getJsonArray("masks").getJsonObject(0).getString("label"));
	}

	@Test
	void testPromptedRescalesBoxesIntoThePostedSpace() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.PROMPTED, 100, 75, List.of(
				Sam2TestFixtures.mask(0, Sam2TestFixtures.binaryMaskPng(100, 75, 5, 10, 15, 20), 5, 10, 15, 20, 0.9d, "person")), 0));

		Sam2NodeOptions options = new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED).setMaxDim(100);
		assertThat(node(options)
			.process(ctx(List.of(Sam2TestFixtures.detectionElement(0, "person", 10, 20, 30, 40, SOURCE_W, SOURCE_H, 0)))))
				.isSuccess();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2Box>> boxes = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).segment(any(), any(), boxes.capture(), any());

		// 200 -> 100 is a factor of 0.5. Sending source-space boxes against a downscaled image is the
		// silent-wrong-answer this conversion exists to prevent.
		Sam2Box box = boxes.getValue().get(0);
		assertEquals(5d, box.x1(), 0.001d);
		assertEquals(10d, box.y1(), 0.001d);
		assertEquals(20d, box.x2(), 0.001d);
		assertEquals(30d, box.y2(), 0.001d);
	}

	@Test
	void testPromptedRescalesNormalizedBoxes() {
		Sam2NodeOptions options = new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED);
		assertThat(node(options).process(ctx(List.of(
			Sam2TestFixtures.normalizedDetectionElement(0, "person", 0.05d, 0.1333d, 0.15d, 0.2667d, SOURCE_W, SOURCE_H)))))
				.isSuccess();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Sam2Box>> boxes = ArgumentCaptor.forClass(List.class);
		verify(sam2Client).segment(any(), any(), boxes.capture(), any());

		// The same box as testPromptedSegmentsTheWiredBoxes, expressed 0..1. Producers disagree about
		// which convention they emit, so the element states it and this guard honours it.
		Sam2Box box = boxes.getValue().get(0);
		assertEquals(10d, box.x1(), 0.5d);
		assertEquals(20d, box.y1(), 0.5d);
		assertEquals(40d, box.x2(), 0.5d);
		assertEquals(60d, box.y2(), 0.5d);
	}

	// ── skip and failure ───────────────────────────────────────────────────

	@Test
	void testPromptedWithNoDetectionsIsSkipped() {
		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED)).process(ctx());

		// A photo where the detector found nothing is a normal outcome, not a failure.
		assertThat(result).isSkipped();
		org.assertj.core.api.Assertions.assertThat(result.getMessage())
			.as("the reason must name the port that would fix it")
			.contains(Sam2Node.IN_DETECTIONS.id());
		verify(sam2Client, times(0)).segment(any(), any(), any(), any());
	}

	@Test
	void testTrackOnAnImageFails() {
		NodeResult result = node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(ctx());

		// 🔴 The test that pins abort() over next(): ctx.failure(msg).next() reports SUCCESS, because
		// NodeContextImpl.next() looks only at the skip reason. Fail, do not skip - the worker was given
		// a job it cannot do.
		assertThat(result).isFailed();
		assertEquals(Sam2Node.FLAG_FAILED, result.get(Sam2Node.OUT_FLAG));
		assertNull(result.get(Sam2Node.OUT_SEGMENTS));
	}

	@Test
	void testSidecarFailureIsFailedNotSuccess() {
		when(sam2Client.segment(any(), any(), any(), any())).thenThrow(new RuntimeException("sidecar down"));

		NodeResult result = node().process(ctx());

		assertThat(result).isFailed();
		assertEquals(Sam2Node.FLAG_FAILED, result.get(Sam2Node.OUT_FLAG));
		assertNull(result.get(Sam2Node.OUT_SEGMENTS));
		assertThat(result).hasElementCount(Sam2Node.OUT_MASKS, 0);
	}

	@Test
	void testFailureDoesNotPoisonCache() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("sidecar down"))
			.thenReturn(twoMasks());

		Sam2Node node = node();
		assertThat(node.process(ctx())).isFailed();

		NodeResult retry = node.process(ctx());
		assertThat(retry).isSuccess();
		assertThat(retry).hasElementCount(Sam2Node.OUT_MASKS, 2);
	}

	@Test
	void testNonVisualMediaIsSkipped() {
		StubLoomMedia audio = new StubLoomMedia(media.absolutePath(), false, false, true, false);
		audio.setSHA512(HASH);

		assertThat(node().process(NodeContext.create(audio))).isSkipped();
	}

	@Test
	void testDisabledNodeIsSkipped() {
		Sam2NodeOptions options = new Sam2NodeOptions();
		options.setEnabled(false);

		assertThat(node(options).process(ctx())).isSkipped();
	}

	// ── cache ──────────────────────────────────────────────────────────────

	@Test
	void testSecondRunServedFromCacheWithoutReinference() {
		Sam2Node node = node();
		NodeResult first = node.process(ctx());
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctx());
		assertThat(second).isSuccess();

		// Assert the whole sequence, not just the count: how many elements come back is how many
		// downstream per-element tasks the engine spawns.
		assertEquals(first.elements(Sam2Node.OUT_MASKS), second.elements(Sam2Node.OUT_MASKS));
		assertEquals(first.get(Sam2Node.OUT_SEGMENTS), second.get(Sam2Node.OUT_SEGMENTS));
		verify(sam2Client, times(1)).segment(any(), any(), any(), any());
	}

	@Test
	void testCacheHitFallsThroughWhenTheManifestWasDeleted() throws Exception {
		Sam2Node node = node();
		NodeResult first = node.process(ctx());
		assertThat(first).isSuccess();

		// Someone cleared metaPath underneath us, or a worker died mid-write. The manifest is the
		// commit marker, so without it the cached entry is a promise the filesystem cannot keep.
		Files.delete(Path.of(manifestOf(first).getString("dir")).resolve(Sam2Node.MANIFEST_NAME));

		assertThat(node.process(ctx())).isSuccess();
		verify(sam2Client, times(2)).segment(any(), any(), any(), any());
	}

	@Test
	void testCacheHitFallsThroughInADebugRun() {
		Sam2Node node = node();
		assertThat(node.process(ctx())).isSuccess();

		// The cache holds the ports and nothing else, so a hit would re-emit paths with no per-mask
		// crops. The same file showing its masks the first time it is examined and not the second is
		// precisely what makes a debugging view untrustworthy.
		NodeResult debug = node.process(NodeContext.create(media, new NodeInputs(Map.of(), Set.of(), null, null, true)));
		assertThat(debug).isSuccess();

		verify(sam2Client, times(2)).segment(any(), any(), any(), any());
		assertNotNull(debug.getPreviews().get(Sam2Node.OUT_MASKS.id() + "#0"), "each mask must carry its own preview");
	}

	@Test
	void testTwoOptionSetsDoNotShareACacheKey() {
		Sam2Node permissive = node(new Sam2NodeOptions().setPointsPerSide(32));
		Sam2Node coarse = node(new Sam2NodeOptions().setPointsPerSide(16));

		String permissiveDir = manifestOf(permissive.process(ctx())).getString("dir");
		String coarseDir = manifestOf(coarse.process(ctx())).getString("dir");

		// Two sam2 nodes in one graph - a fine pass and a coarse one - must not serve each other's
		// masks, and must not collide on disk either.
		verify(sam2Client, times(2)).segment(any(), any(), any(), any());
		assertNotEquals(permissiveDir, coarseDir, "the option digest must reach the artifact path, not only the cache key");
	}

	@Test
	void testDifferentBoxesDoNotShareACacheKey() {
		Sam2NodeOptions options = new Sam2NodeOptions().setMode(Sam2Mode.PROMPTED);

		String firstDir = manifestOf(node(options)
			.process(ctx(List.of(Sam2TestFixtures.detectionElement(0, "person", 10, 20, 30, 40, SOURCE_W, SOURCE_H, 0)))))
				.getString("dir");
		String secondDir = manifestOf(node(options)
			.process(ctx(List.of(Sam2TestFixtures.detectionElement(0, "person", 90, 20, 30, 40, SOURCE_W, SOURCE_H, 0)))))
				.getString("dir");

		// Same options, different boxes. Without the boxes in the digest, a re-run against better
		// detections would be handed the first run's masks.
		verify(sam2Client, times(2)).segment(any(), any(), any(), any());
		assertNotEquals(firstDir, secondDir);
	}

	@Test
	void testNothingIsPostedToTheTrackEndpointForAStill() {
		assertThat(node().process(ctx())).isSuccess();
		verify(sam2Client, times(0)).track(anyList(), anyList(), anyList(), any());
	}
}
