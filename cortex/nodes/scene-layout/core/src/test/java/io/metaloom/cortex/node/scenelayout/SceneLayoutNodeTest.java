package io.metaloom.cortex.node.scenelayout;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * End-to-end unit test for {@link SceneLayoutNode} against a real depth PNG on disk and a real
 * upstream detections payload. No model, no GPU, no Loom - the map is synthetic and the boxes are
 * hand-placed, so the expected layout is exact.
 */
class SceneLayoutNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;
	private static final int MAP_W = 200;
	private static final int MAP_H = 100;

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private StubLoomMedia media;
	private File mapFile;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		// Left half near, right half far. The map is half the image's size in both axes, so the
		// node has to project boxes before sampling - which is exactly what we want exercised.
		mapFile = new File(tempDir, "depth.png");
		SceneLayoutFixtures.writeSplitMap(mapFile, MAP_W, MAP_H);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "photo.jpg", "fake-image");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private SceneLayoutNode node() {
		return node(new SceneLayoutNodeOptions());
	}

	private SceneLayoutNode node(SceneLayoutNodeOptions options) {
		return new SceneLayoutNode(null, cortexOptions, options);
	}

	/** Two faces: one in the near (left) half of the image, one in the far (right) half. */
	private Map<String, Map<String, Object>> upstream() {
		return upstream(SceneLayoutFixtures.detections(IMAGE_W, IMAGE_H,
			SceneLayoutFixtures.detection(0, "face", 40, 40, 80, 80),
			SceneLayoutFixtures.detection(1, "face", 280, 40, 80, 80)));
	}

	private Map<String, Map<String, Object>> upstream(JsonObject detections) {
		Map<String, Map<String, Object>> up = new HashMap<>();
		up.put("depthmap", Map.of(
			"depthmap_path", mapFile.getAbsolutePath(),
			"depthmap_meta", SceneLayoutFixtures.depthMeta(mapFile, MAP_W, MAP_H, IMAGE_W, IMAGE_H).encode()));
		up.put("facedetect", Map.of("detections", detections.encode()));
		return up;
	}

	private NodeContext<LoomMedia> ctx() {
		return NodeContext.create(media, upstream());
	}

	private JsonObject payloadOf(NodeResult result) {
		String json = result.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_RESULT);
		assertNotNull(json, "the node must emit the layout payload");
		return new JsonObject(json);
	}

	@Test
	void testRelatesTwoFacesAcrossTheDepthSplit() {
		NodeResult result = node().process(ctx());
		assertThat(result).isSuccess();

		assertEquals(2, result.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_OBJECTS));

		JsonObject payload = payloadOf(result);
		JsonArray objects = payload.getJsonArray("objects");
		assertEquals(2, objects.size());

		// face-0 sits in the near half, face-1 in the far half.
		JsonObject near = objects.getJsonObject(0);
		JsonObject far = objects.getJsonObject(1);
		assertEquals("face-0", near.getString("id"));
		org.assertj.core.api.Assertions.assertThat(near.getJsonObject("depth").getDouble("near")).isGreaterThan(0.8);
		org.assertj.core.api.Assertions.assertThat(far.getJsonObject("depth").getDouble("near")).isLessThan(0.2);
		assertEquals("FOREGROUND", near.getJsonObject("depth").getString("band"));
		assertEquals("BACKGROUND", far.getJsonObject("depth").getString("band"));

		// ...and the relation follows.
		org.assertj.core.api.Assertions.assertThat(predicates(payload))
			.contains("face-0 IN_FRONT_OF face-1", "face-1 BEHIND face-0", "face-0 LEFT_OF face-1");
	}

	@Test
	void testEmitsReadablePhrases() {
		JsonObject payload = payloadOf(node().process(ctx()));

		org.assertj.core.api.Assertions.assertThat(payload.getJsonArray("phrases").getList())
			.contains("face-0 is in front of face-1", "face-0 is in the foreground", "face-1 is in the background");
	}

	@Test
	void testPhrasesCanBeDisabled() {
		JsonObject payload = payloadOf(node(new SceneLayoutNodeOptions().setEmitPhrases(false)).process(ctx()));

		org.assertj.core.api.Assertions.assertThat(payload.getJsonArray("phrases")).isEmpty();
	}

	@Test
	void testPayloadCarriesDepthProvenance() {
		JsonObject depth = payloadOf(node().process(ctx())).getJsonObject("depth");

		assertEquals(SceneLayoutFixtures.MODEL, depth.getString("model"));
		assertEquals("NEARNESS", depth.getString("convention"));
		assertEquals(MAP_W, depth.getInteger("mapWidth"));
		assertEquals(MAP_H, depth.getInteger("mapHeight"));
		assertNotNull(depth.getJsonObject("sceneQuantiles"));
	}

	@Test
	void testBoxesAreReportedInImageSpaceNotMapSpace() {
		JsonObject bbox = payloadOf(node().process(ctx()))
			.getJsonArray("objects").getJsonObject(0).getJsonObject("bbox");

		// The consumer thinks in image coordinates; map space is an internal detail.
		assertEquals(40d, bbox.getDouble("x"));
		assertEquals(80d, bbox.getDouble("w"));
	}

	@Test
	void testSkippedWhenNoDepthMap() {
		Map<String, Map<String, Object>> up = new HashMap<>(upstream());
		up.remove("depthmap");

		assertThat(node().process(NodeContext.create(media, up))).isSkipped();
	}

	@Test
	void testSkippedWhenDepthArtifactIsMissing() {
		// The affinity mistake: this node ran on a worker that never produced the map.
		Map<String, Map<String, Object>> up = new HashMap<>(upstream());
		File absent = new File(tempDir, "nope.png");
		up.put("depthmap", Map.of(
			"depthmap_path", absent.getAbsolutePath(),
			"depthmap_meta", SceneLayoutFixtures.depthMeta(absent, MAP_W, MAP_H, IMAGE_W, IMAGE_H).encode()));

		assertThat(node().process(NodeContext.create(media, up))).isSkipped();
	}

	@Test
	void testSkippedOnUnknownDepthConvention() {
		Map<String, Map<String, Object>> up = new HashMap<>(upstream());
		up.put("depthmap", Map.of(
			"depthmap_path", mapFile.getAbsolutePath(),
			"depthmap_meta", SceneLayoutFixtures.depthMeta(mapFile, MAP_W, MAP_H, IMAGE_W, IMAGE_H)
				.put("convention", "METRIC_METERS").encode()));

		// Refusing beats guessing: the wrong reading inverts every relation while still "succeeding".
		assertThat(node().process(NodeContext.create(media, up))).isSkipped();
	}

	@Test
	void testSkippedWhenNoDetections() {
		assertThat(node().process(NodeContext.create(media, upstream(
			SceneLayoutFixtures.detections(IMAGE_W, IMAGE_H))))).isSkipped();
	}

	@Test
	void testSkippedWithASingleDetection() {
		// One object has nothing to relate to; a component row for it would carry no information.
		assertThat(node().process(NodeContext.create(media, upstream(
			SceneLayoutFixtures.detections(IMAGE_W, IMAGE_H,
				SceneLayoutFixtures.detection(0, "face", 40, 40, 80, 80)))))).isSkipped();
	}

	@Test
	void testObjectsAreCappedLargestFirst() {
		JsonObject detections = SceneLayoutFixtures.detections(IMAGE_W, IMAGE_H,
			SceneLayoutFixtures.detection(0, "face", 10, 10, 20, 20),
			SceneLayoutFixtures.detection(1, "face", 60, 10, 100, 100),
			SceneLayoutFixtures.detection(2, "face", 250, 10, 90, 90));

		NodeResult result = node(new SceneLayoutNodeOptions().setMaxObjects(2))
			.process(NodeContext.create(media, upstream(detections)));
		assertThat(result).isSuccess();

		JsonObject payload = payloadOf(result);
		assertEquals(2, payload.getJsonArray("objects").size());
		// The truncation is reported rather than implied.
		assertEquals(1, payload.getJsonObject("truncated").getInteger("objects"));
		// The two largest boxes survived; the 20x20 one did not.
		org.assertj.core.api.Assertions.assertThat(ids(payload)).containsExactlyInAnyOrder("face-1", "face-2");
	}

	@Test
	void testSecondRunServedFromCache() {
		SceneLayoutNode node = node();
		NodeResult first = node.process(ctx());
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctx());
		assertThat(second).isSuccess();
		assertEquals(first.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_RESULT), second.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_RESULT));
		assertEquals(first.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_OBJECTS), second.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_OBJECTS));
	}

	@Test
	void testNonImageIsSkipped() {
		StubLoomMedia video = new StubLoomMedia(media.absolutePath(), true, false, false, false);
		video.setSHA512(HASH);

		assertThat(node().process(NodeContext.create(video, upstream()))).isSkipped();
	}

	@Test
	void testDisabledNodeIsSkipped() {
		SceneLayoutNodeOptions options = new SceneLayoutNodeOptions();
		options.setEnabled(false);

		assertThat(node(options).process(ctx())).isSkipped();
	}

	@Test
	void testMalformedUpstreamPayloadDoesNotThrow() {
		Map<String, Map<String, Object>> up = new HashMap<>(upstream());
		up.put("facedetect", Map.of("detections", "not json at all"));

		// A garbled upstream output must surface as a node result, never as an escaped exception
		// that takes the pipeline item down with it.
		assertThatNoException().isThrownBy(() -> node().process(NodeContext.create(media, up)));
	}

	private java.util.List<String> predicates(JsonObject payload) {
		JsonArray relations = payload.getJsonArray("relations");
		return relations.stream()
			.map(JsonObject.class::cast)
			.map(r -> r.getString("subject") + " " + r.getString("predicate") + " " + r.getString("object"))
			.toList();
	}

	private java.util.List<String> ids(JsonObject payload) {
		return payload.getJsonArray("objects").stream()
			.map(JsonObject.class::cast)
			.map(o -> o.getString("id"))
			.toList();
	}
}
