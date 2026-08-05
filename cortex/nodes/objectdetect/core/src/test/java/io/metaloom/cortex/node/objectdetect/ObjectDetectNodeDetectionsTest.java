package io.metaloom.cortex.node.objectdetect;

import static io.metaloom.cortex.node.objectdetect.assertj.ObjectDetectNodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * The output ports on the image path.
 *
 * <p>
 * The element shape asserted here is a contract, not an implementation detail: {@code scene-layout}
 * and {@code dominant-color} parse these documents, and they were written against {@code facedetect}
 * before this node existed. Every field but {@code classId} therefore has to match what that node
 * emits, including the {@code coordinates} marker that keeps consumers clear of the {@code detection}
 * table's ambiguous geometry convention.
 * </p>
 *
 * <p>
 * The detector is mocked throughout — the real one loads {@code libyolib.so}, an ONNX Runtime and a
 * model, and the detection algorithm is not what is under test. Only the image path is exercised;
 * the video path needs the native Video4j runtime.
 * </p>
 */
class ObjectDetectNodeDetectionsTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private ObjectDetector detector;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		detector = mock(ObjectDetector.class);

		// A real 320x240 JPEG, because the node decodes it and reports its dimensions.
		File imageFile = new File(tempDir, "street.jpg");
		ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);

		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private static ObjectDetection object(int x, int y, int w, int h, float confidence, int classId, String label) {
		return new ObjectDetection(new BoundingBox(x, y, w, h), confidence, classId, label);
	}

	private ObjectDetectNode node() {
		return node(new ObjectDetectNodeOptions());
	}

	private ObjectDetectNode node(ObjectDetectNodeOptions options) {
		return new ObjectDetectNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), options,
			detector, new VideoObjectScanner(detector));
	}

	/** The elements of the {@code MANY} detections port, decoded, in emission order. */
	private static List<JsonObject> detections(NodeResult result) {
		List<String> elements = result.elements(ObjectDetectNode.OUT_DETECTIONS);
		assertNotNull(elements, "the node must emit the detections port");
		return elements.stream().map(JsonObject::new).toList();
	}

	@Test
	void testEmitsOneElementPerObjectWithLabelMarkerAndDimensions() {
		doReturn(List.of(
			object(10, 20, 30, 40, 0.91f, 14, "person"),
			object(100, 50, 60, 60, 0.72f, 6, "car"))).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();
		// scalar/integer is widened to Long at the port boundary.
		assertEquals(2L, result.get(ObjectDetectNode.OUT_OBJECT_COUNT));
		assertEquals("SUCCESS", result.get(ObjectDetectNode.OUT_FLAG));

		List<JsonObject> items = detections(result);
		// One element per object is the whole point: the element count is what the engine reads to
		// decide how many per-object tasks to spawn downstream.
		assertEquals(2, items.size());
		assertThat(result).hasElementCount(ObjectDetectNode.OUT_DETECTIONS, 2);

		JsonObject first = items.get(0);
		assertEquals(0, first.getInteger("index"));
		assertEquals("object", first.getString("type"));
		assertEquals("person", first.getString("label"));
		assertEquals(14, first.getInteger("classId"));
		assertEquals(0, first.getInteger("frame"), "an image is frame 0, matching the detection table");
		assertEquals(10, first.getJsonObject("bbox").getInteger("x"));
		assertEquals(20, first.getJsonObject("bbox").getInteger("y"));
		assertEquals(30, first.getJsonObject("bbox").getInteger("w"));
		assertEquals(40, first.getJsonObject("bbox").getInteger("h"));
		assertEquals(0.91f, first.getFloat("confidence"), 0.0001f);

		// The marker plus the dimensions travel on *every* element rather than in a wrapper - a
		// per-object consumer receives exactly one element and nothing else.
		for (JsonObject item : items) {
			assertEquals("ABSOLUTE_PIXELS", item.getString("coordinates"));
			assertEquals(320, item.getInteger("imageWidth"));
			assertEquals(240, item.getInteger("imageHeight"));
		}

		// Indices are dense and ordered, so a consumer can build stable ids from them.
		assertEquals(1, items.get(1).getInteger("index"));
		assertEquals("car", items.get(1).getString("label"));
	}

	@Test
	void testEmitsDistinctLabelsForTheTagNode() {
		doReturn(List.of(
			object(0, 0, 10, 10, 0.9f, 6, "car"),
			object(20, 0, 10, 10, 0.8f, 6, "car"),
			object(40, 0, 10, 10, 0.7f, 14, "person"))).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();

		// Distinct, in first-seen order. Three detections of two classes must tag the asset twice, not
		// three times - the tag node turns every element of this port into a tag.
		assertEquals(List.of("car", "person"), result.elements(ObjectDetectNode.OUT_LABELS));
		assertEquals(3L, result.get(ObjectDetectNode.OUT_OBJECT_COUNT), "the count is detections, not classes");
	}

	@Test
	void testEmitsNoElementsWhenNothingIsDetected() {
		doReturn(List.of()).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();

		// A MANY port with nothing to emit is simply absent - there is no "empty list" element to carry.
		// What tells "ran, found nothing" apart from "never ran" is the count and the flag.
		assertEquals(List.of(), detections(result));
		assertThat(result).hasNoOutput(ObjectDetectNode.OUT_DETECTIONS);
		assertThat(result).hasNoOutput(ObjectDetectNode.OUT_LABELS);
		assertEquals(0L, result.get(ObjectDetectNode.OUT_OBJECT_COUNT));
		assertEquals("NONE", result.get(ObjectDetectNode.OUT_FLAG));
	}

	@Test
	void testDropsDetectionsBelowMinConfidence() {
		doReturn(List.of(
			object(0, 0, 10, 10, 0.95f, 14, "person"),
			object(20, 0, 10, 10, 0.41f, 6, "car"))).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node(new ObjectDetectNodeOptions().setMinConfidence(0.9f)).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		assertEquals(1, detections(result).size());
		assertEquals("person", detections(result).get(0).getString("label"));
	}

	@Test
	void testKeepsOnlyTheConfiguredClasses() {
		doReturn(List.of(
			object(0, 0, 10, 10, 0.9f, 14, "person"),
			object(20, 0, 10, 10, 0.9f, 6, "car"),
			object(40, 0, 10, 10, 0.9f, 8, "cat"))).when(detector).detect(any(BufferedImage.class));

		// Mixed case on purpose: the filter is matched case-insensitively against the labels file, so an
		// author writing "Person" must not silently get nothing.
		ObjectDetectNodeOptions options = new ObjectDetectNodeOptions().setClassFilter(Set.of("Person", "CAT"));
		NodeResult result = node(options).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		assertEquals(List.of("person", "cat"),
			detections(result).stream().map(item -> item.getString("label")).toList());
	}

	@Test
	void testCapsTheDetectionsAndSaysSo() {
		doReturn(List.of(
			object(0, 0, 10, 10, 0.9f, 14, "person"),
			object(20, 0, 10, 10, 0.9f, 14, "person"),
			object(40, 0, 10, 10, 0.9f, 14, "person"))).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node(new ObjectDetectNodeOptions().setMaxDetections(2)).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		assertEquals(2, detections(result).size());
		// Its own flag rather than SUCCESS: "this is what is in the file" and "this is the first two of
		// what is in the file" are different answers, and a consumer of a truncated set needs to know.
		assertEquals("CAPPED", result.get(ObjectDetectNode.OUT_FLAG));
	}

	@Test
	void testLabelsAnUnknownClassByItsId() {
		// A class id outside the labels file still has a box worth keeping. yolo4j reports null for it,
		// and a null in the indexed detection.label column is indistinguishable from "this producer does
		// not do labels".
		doReturn(List.of(object(0, 0, 10, 10, 0.9f, 99, null))).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();
		assertEquals("class-99", detections(result).get(0).getString("label"));
		assertEquals(List.of("class-99"), result.elements(ObjectDetectNode.OUT_LABELS));
	}

	@Test
	void testDetectionElementsSurviveACacheHit() {
		doReturn(List.of(
			object(10, 20, 30, 40, 0.9f, 14, "person"),
			object(100, 50, 60, 60, 0.8f, 6, "car"))).when(detector).detect(any(BufferedImage.class));

		ObjectDetectNode node = node();
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		// The cache holds the elements as lists, so a hit re-emits the *same sequences* a fresh run
		// would. A cache that collapsed them into one value would silently change how many downstream
		// per-element tasks the engine spawns.
		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.elements(ObjectDetectNode.OUT_DETECTIONS), second.elements(ObjectDetectNode.OUT_DETECTIONS));
		assertEquals(first.elements(ObjectDetectNode.OUT_LABELS), second.elements(ObjectDetectNode.OUT_LABELS));
		assertThat(second).hasElementCount(ObjectDetectNode.OUT_DETECTIONS, 2);
	}

	@Test
	void testAnEmptyResultStillCaches() {
		doReturn(List.of()).when(detector).detect(any(BufferedImage.class));

		ObjectDetectNode node = node();
		assertThat(node.process(NodeContext.create(media))).isSuccess();

		// Snapshotting the cache must not read the detections port unconditionally: a MANY port that
		// emitted nothing is absent entirely, and reading it unguarded throws.
		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals("NONE", second.get(ObjectDetectNode.OUT_FLAG));
		assertEquals(0L, second.get(ObjectDetectNode.OUT_OBJECT_COUNT));
	}

	@Test
	void testTwoConfigurationsDoNotShareCachedResults() {
		doReturn(List.of(
			object(0, 0, 10, 10, 0.95f, 14, "person"),
			object(20, 0, 10, 10, 0.5f, 6, "car"))).when(detector).detect(any(BufferedImage.class));

		// Same media, same worker, different thresholds. Keying the cache on the path alone - which is
		// what a node that can only appear once gets away with - would serve the first node's answer to
		// the second.
		ObjectDetectNode permissive = node(new ObjectDetectNodeOptions().setMinConfidence(0.4f));
		ObjectDetectNode strict = node(new ObjectDetectNodeOptions().setMinConfidence(0.9f));

		assertEquals(2, detections(permissive.process(NodeContext.create(media))).size());
		assertEquals(1, detections(strict.process(NodeContext.create(media))).size());
	}

	@Test
	void testADetectorFailureIsReportedAsFailed() {
		doThrow(new IllegalStateException("model missing")).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));

		// abort(), not next(): NodeContextImpl.next() looks only at the skip reason, so a failure
		// returned through it reports SUCCESS. facedetect's video path still does that.
		assertEquals(ResultState.FAILED, result.getState());
	}

	@Test
	void testImagePreviewsCarryNoFrame() {
		doReturn(List.of(object(10, 20, 30, 40, 0.9f, 14, "person"))).when(detector).detect(any(BufferedImage.class));

		// capturePreviews is what turns the pictures on at all; a production run pays for none of this.
		NodeResult result = node().process(NodeContext.create(media,
			new NodeInputs(Map.of(), Set.of(), null, null, true)));
		assertThat(result).isSuccess();

		// A still is not frame 0 of anything. The overlay filters boxes by the preview's frame, so a zero
		// here would be read as "only draw the detections stamped frame 0".
		NodePreview port = result.getPreviews().get(ObjectDetectNode.OUT_DETECTIONS.id());
		assertNotNull(port, "the detections port must carry the frame the boxes were measured on");
		assertNull(port.getFrame(), "an image preview must not claim to be a video frame");

		NodePreview crop = result.getPreviews().get(ObjectDetectNode.OUT_DETECTIONS.id() + "#0");
		assertNotNull(crop, "each detection must carry its own crop");
		assertNull(crop.getFrame());
	}
}
