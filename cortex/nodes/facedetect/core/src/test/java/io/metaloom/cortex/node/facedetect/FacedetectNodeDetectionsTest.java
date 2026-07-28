package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Covers the {@code detections} output key on the image path.
 *
 * <p>
 * The boxes used to reach only the {@code detection} table, which meant a downstream node had to go
 * through Loom to see them - impossible offline, and impossible before the write had landed. This
 * test pins the payload shape that {@code scene-layout} consumes, including the explicit coordinate
 * marker that keeps it clear of the table's ambiguous geometry convention.
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

	private JsonObject detections(NodeResult result) {
		String json = result.get(FacedetectNode.OUTPUT_DETECTIONS);
		assertNotNull(json, "the node must emit the detections payload");
		return new JsonObject(json);
	}

	@Test
	void testEmitsBoxesWithCoordinateMarkerAndDimensions() {
		doReturn(List.of(face(10, 20, 30, 40), face(100, 50, 60, 60))).when(inspireface).detectFaces(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();
		assertEquals(2, result.get(FacedetectNode.OUTPUT_FACE_COUNT));

		JsonObject payload = detections(result);
		// The marker plus the dimensions are what make this payload unambiguous - the detection
		// table documents normalized 0-1 while this node writes pixels, and nothing validates either.
		assertEquals("ABSOLUTE_PIXELS", payload.getString("coordinates"));
		assertEquals(320, payload.getInteger("imageWidth"));
		assertEquals(240, payload.getInteger("imageHeight"));

		JsonArray items = payload.getJsonArray("detections");
		assertEquals(2, items.size());

		JsonObject first = items.getJsonObject(0);
		assertEquals(0, first.getInteger("index"));
		assertEquals("face", first.getString("type"));
		assertEquals("face", first.getString("label"));
		assertEquals(0, first.getInteger("frame"));
		assertEquals(10, first.getJsonObject("bbox").getInteger("x"));
		assertEquals(20, first.getJsonObject("bbox").getInteger("y"));
		assertEquals(30, first.getJsonObject("bbox").getInteger("w"));
		assertEquals(40, first.getJsonObject("bbox").getInteger("h"));

		// Indices are dense and ordered, so a consumer can build stable ids from them.
		assertEquals(1, items.getJsonObject(1).getInteger("index"));
	}

	@Test
	void testEmitsEmptyPayloadWhenNoFaces() {
		doReturn(List.of()).when(inspireface).detectFaces(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSuccess();

		// An explicit empty list beats an absent key: the consumer can tell "ran, found nothing"
		// apart from "never ran".
		JsonObject payload = detections(result);
		assertEquals(0, payload.getJsonArray("detections").size());
		assertEquals("NONE", result.get(FacedetectNode.OUTPUT_FACEDETECT_FLAG));
	}

	@Test
	void testDetectionsSurviveACacheHit() {
		doReturn(List.of(face(10, 20, 30, 40))).when(inspireface).detectFaces(any(BufferedImage.class));

		FacedetectNode node = node();
		NodeResult first = node.process(NodeContext.create(media));
		assertThat(first).isSuccess();

		// The node snapshots ctx.outputs() wholesale, so the new key rides along with the existing
		// cache entry rather than needing its own handling.
		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertEquals(first.get(FacedetectNode.OUTPUT_DETECTIONS), second.get(FacedetectNode.OUTPUT_DETECTIONS));
	}

	@Test
	void testNullFaceListDoesNotThrow() {
		doReturn(null).when(inspireface).detectFaces(any(BufferedImage.class));

		assertThatNoException().isThrownBy(() -> node().process(NodeContext.create(media)));
	}
}
