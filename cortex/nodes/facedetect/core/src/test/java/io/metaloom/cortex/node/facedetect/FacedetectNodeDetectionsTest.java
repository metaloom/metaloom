package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonObject;

/**
 * Covers the {@code detections} output port on the image path.
 *
 * <p>
 * The boxes used to reach only the {@code detection} table, which meant a downstream node had to go
 * through Loom to see them - impossible offline, and impossible before the write had landed. This
 * test pins the element shape that {@code scene-layout} consumes, including the explicit coordinate
 * marker that keeps it clear of the table's ambiguous geometry convention.
 * </p>
 *
 * <p>
 * {@code detections} is a {@code MANY} port carrying <strong>one element per face</strong>, not one
 * document listing them all. That is what lets the engine fan out per face, so the shape asserted
 * here is a per-element one: every element repeats the coordinate convention and the dimensions,
 * because an element travels downstream on its own.
 * </p>
 *
 * <p>
 * Only the image path is exercised: the video path needs the native Video4j runtime.
 * </p>
 */
class FacedetectNodeDetectionsTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private InspireFacedetector inspireface;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		inspireface = mock(InspireFacedetector.class);

		// A real 320x240 JPEG, because the node decodes it and reports its dimensions.
		File imageFile = new File(tempDir, "group.jpg");
		ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);

		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private static Face face(int x, int y, int w, int h) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		return face;
	}

	private FacedetectNode node() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		return new FacedetectNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), options,
			inspireface, mock(VideoFaceScanner.class));
	}

	/** The elements of the {@code MANY} detections port, decoded, in emission order. */
	private List<JsonObject> detections(NodeResult result) {
		List<String> elements = result.elements(FacedetectNode.OUT_DETECTIONS);
		assertNotNull(elements, "the node must emit the detections port");
		return elements.stream().map(JsonObject::new).toList();
	}

	@Test
	void testEmitsOneElementPerFaceWithCoordinateMarkerAndDimensions() {
		doReturn(List.of(face(10, 20, 30, 40), face(100, 50, 60, 60))).when(inspireface).detectFaces(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();
		// scalar/integer is widened to Long at the port boundary.
		assertEquals(2L, result.get(FacedetectNode.OUT_FACE_COUNT));

		List<JsonObject> items = detections(result);
		// One element per face is the whole point: the element count is what the engine reads to
		// decide how many per-face tasks to spawn downstream.
		assertEquals(2, items.size());
		assertThat(result).hasElementCount(FacedetectNode.OUT_DETECTIONS, 2);

		JsonObject first = items.get(0);
		assertEquals(0, first.getInteger("index"));
		assertEquals("face", first.getString("type"));
		assertEquals("face", first.getString("label"));
		assertEquals(0, first.getInteger("frame"));
		assertEquals(10, first.getJsonObject("bbox").getInteger("x"));
		assertEquals(20, first.getJsonObject("bbox").getInteger("y"));
		assertEquals(30, first.getJsonObject("bbox").getInteger("w"));
		assertEquals(40, first.getJsonObject("bbox").getInteger("h"));

		// The marker plus the dimensions travel on *every* element rather than in a wrapper - a
		// per-face consumer receives exactly one element and nothing else, and the detection table
		// documents normalized 0-1 while this node writes pixels with nothing validating either.
		for (JsonObject item : items) {
			assertEquals("ABSOLUTE_PIXELS", item.getString("coordinates"));
			assertEquals(320, item.getInteger("imageWidth"));
			assertEquals(240, item.getInteger("imageHeight"));
		}

		// Indices are dense and ordered, so a consumer can build stable ids from them.
		assertEquals(1, items.get(1).getInteger("index"));
	}

	@Test
	void testEmitsNoElementsWhenNoFaces() {
		doReturn(List.of()).when(inspireface).detectFaces(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();

		// A MANY port with nothing to emit is simply absent - there is no "empty list" element to
		// carry. What tells "ran, found nothing" apart from "never ran" is the count and the flag,
		// which are emitted either way.
		assertEquals(List.of(), detections(result));
		assertThat(result).hasNoOutput(FacedetectNode.OUT_DETECTIONS);
		assertEquals(0L, result.get(FacedetectNode.OUT_FACE_COUNT));
		assertEquals("NONE", result.get(FacedetectNode.OUT_FLAG));
	}

	@Test
	void testDetectionElementsSurviveACacheHit() {
		doReturn(List.of(face(10, 20, 30, 40), face(100, 50, 60, 60))).when(inspireface).detectFaces(any(BufferedImage.class));

		FacedetectNode node = node();
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		// The cache holds the elements as a list, so a hit re-emits the *same sequence* a fresh run
		// would. A cache that collapsed them into one value would silently change how many
		// downstream per-element tasks the engine spawns.
		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.elements(FacedetectNode.OUT_DETECTIONS), second.elements(FacedetectNode.OUT_DETECTIONS));
		assertThat(second).hasElementCount(FacedetectNode.OUT_DETECTIONS, 2);
	}

	@Test
	void testAFaceLessImageStillCaches() {
		doReturn(List.of()).when(inspireface).detectFaces(any(BufferedImage.class));

		FacedetectNode node = node();
		assertThat(node.process(NodeContext.create(media))).isSuccess();

		// Snapshotting the cache used to read the detections port unconditionally, which threw for
		// an image with no faces because a MANY port that emitted nothing is absent entirely.
		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals("NONE", second.get(FacedetectNode.OUT_FLAG));
		assertEquals(0L, second.get(FacedetectNode.OUT_FACE_COUNT));
	}

	@Test
	void testNullFaceListDoesNotThrow() {
		doReturn(null).when(inspireface).detectFaces(any(BufferedImage.class));

		assertThatNoException().isThrownBy(() -> node().process(NodeContext.create(media)));
	}

	@Test
	void testImagePreviewsCarryNoFrame() {
		doReturn(List.of(face(10, 20, 30, 40), face(100, 50, 60, 60))).when(inspireface).detectFaces(any(BufferedImage.class));

		// capturePreviews is what turns the pictures on at all; a production run pays for none of this.
		NodeResult result = node().process(NodeContext.create(media,
			new NodeInputs(Map.of(), Set.of(), null, null, true)));
		assertThat(result).isSuccess();

		// A still is not frame 0 of anything. The overlay filters boxes by the preview's frame, so a
		// zero here would be read as "only draw the detections stamped frame 0" — which on the image
		// path is all of them today, and would silently become none the moment a detector stamped a
		// real frame index. Absent is the only honest value.
		NodePreview port = result.getPreviews().get(FacedetectNode.OUT_DETECTIONS.id());
		assertNotNull(port, "the detections port must carry the frame the boxes were measured on");
		assertNull(port.getFrame(), "an image preview must not claim to be a video frame");

		NodePreview crop = result.getPreviews().get(FacedetectNode.OUT_DETECTIONS.id() + "#0");
		assertNotNull(crop, "each detection must carry its own crop");
		assertNull(crop.getFrame());
	}
}
