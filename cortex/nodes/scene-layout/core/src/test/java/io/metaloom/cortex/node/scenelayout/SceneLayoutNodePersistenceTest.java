package io.metaloom.cortex.node.scenelayout;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Verifies the two-step persistence contract of {@link SceneLayoutNode}: on success it upserts one
 * {@code asset_json_comp} row ({@code schemaType=scene-layout}) and <em>then</em> records an
 * {@code asset_node_result} ledger row pointing at it; on failure it records a FAILED ledger row and
 * writes no component.
 *
 * <p>
 * Also covers the Loom read-back path for detections, including its coordinate-convention
 * heuristic.
 * </p>
 */
class SceneLayoutNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;
	private static final int MAP_W = 200;
	private static final int MAP_H = 100;

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private StubLoomMedia media;
	private CortexOptions cortexOptions;
	private File mapFile;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<JsonCompResponse> jsonCompReq = mock(LoomClientRequest.class);
		when(jsonCompReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(jsonCompReq);

		LoomClientRequest<NodeResultResponse> nodeResultReq = mock(LoomClientRequest.class);
		when(nodeResultReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);

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
		return new SceneLayoutNode(client, cortexOptions, options);
	}

	private Map<String, Map<String, Object>> depthOnly() {
		Map<String, Map<String, Object>> up = new HashMap<>();
		up.put("depthmap", Map.of(
			"depthmap_path", mapFile.getAbsolutePath(),
			"depthmap_meta", SceneLayoutFixtures.depthMeta(mapFile, MAP_W, MAP_H, IMAGE_W, IMAGE_H).encode()));
		return up;
	}

	private NodeContext<LoomMedia> ctxWithUpstreamBoxes() {
		Map<String, Map<String, Object>> up = depthOnly();
		up.put("facedetect", Map.of("detections", SceneLayoutFixtures.detections(IMAGE_W, IMAGE_H,
			SceneLayoutFixtures.detection(0, "face", 40, 40, 80, 80),
			SceneLayoutFixtures.detection(1, "face", 280, 40, 80, 80)).encode()));
		return NodeContext.create(media, up);
	}

	@SuppressWarnings("unchecked")
	private void stubLoomDetections(DetectionResponse... rows) throws Exception {
		LoomClientRequest<DetectionListResponse> req = mock(LoomClientRequest.class);
		DetectionListResponse body = new DetectionListResponse();
		for (DetectionResponse row : rows) {
			body.add(row);
		}
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
		when(client.listAssetDetections(any(UUID.class))).thenReturn(req);
	}

	private static DetectionResponse pixelRow(float x, float y, float w, float h) {
		return (DetectionResponse) new DetectionResponse()
			.setType("face")
			.setBboxX(x).setBboxY(y).setBboxWidth(w).setBboxHeight(h)
			.setConfidence(1.0f);
	}

	@Test
	void testWritesJsonCompAndLedgerOnSuccess() {
		assertThat(node().process(ctxWithUpstreamBoxes())).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "scene-layout".equals(r.getNodeKind())
			&& "scene-layout".equals(r.getSchemaType())
			// One row per asset in v1; the variant is reserved for a frame number once video lands.
			&& "".equals(r.getVariant())
			// The layout is only as good as the depth behind it, so that model is the producer version.
			&& SceneLayoutFixtures.MODEL.equals(r.getProducerVersion())
			&& r.getData().getJsonArray("objects").size() == 2
			&& !r.getData().getJsonArray("relations").isEmpty()));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "scene-layout".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testRecordsFailedLedgerWhenComponentWriteFails() {
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still completes, but the ledger records it.
		node().process(ctxWithUpstreamBoxes());

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	@Test
	void testSkippedRunWritesNothing() throws Exception {
		// No detections anywhere: a skip, and nothing may be persisted for it.
		stubLoomDetections();

		assertThat(node().process(NodeContext.create(media, depthOnly()))).isSkipped();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client, never()).createAssetNodeResult(any(), any());
	}

	@Test
	void testFallsBackToLoomWhenNoUpstreamBoxes() throws Exception {
		stubLoomDetections(
			pixelRow(40, 40, 80, 80),
			pixelRow(280, 40, 80, 80));

		assertThat(node().process(NodeContext.create(media, depthOnly()))).isSuccess();

		verify(client).listAssetDetections(assetUuid);
		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> {
			JsonObject first = r.getData().getJsonArray("objects").getJsonObject(0);
			// Read-back rows are tagged with their origin so a payload never hides where its boxes came from.
			return "loom".equals(first.getString("source")) && r.getData().getJsonArray("objects").size() == 2;
		}));
	}

	@Test
	void testUpstreamBoxesWinOverLoom() throws Exception {
		stubLoomDetections(pixelRow(0, 0, 10, 10));

		assertThat(node().process(ctxWithUpstreamBoxes())).isSuccess();

		// The upstream payload states its coordinate convention, so it is always preferred.
		verify(client, never()).listAssetDetections(any(UUID.class));
	}

	@Test
	void testLoomFallbackCanBeDisabled() throws Exception {
		stubLoomDetections(pixelRow(40, 40, 80, 80), pixelRow(280, 40, 80, 80));

		assertThat(node(new SceneLayoutNodeOptions().setAllowLoomFallback(false))
			.process(NodeContext.create(media, depthOnly()))).isSkipped();

		verify(client, never()).listAssetDetections(any(UUID.class));
	}

	@Test
	void testNormalizedLoomRowsAreRefusedRatherThanGuessed() throws Exception {
		// Rows that look normalized cannot be turned into pixels - nothing records the source image
		// size - so the node declines instead of inventing a scale.
		stubLoomDetections(
			(DetectionResponse) new DetectionResponse().setType("face")
				.setBboxX(0.1f).setBboxY(0.1f).setBboxWidth(0.2f).setBboxHeight(0.2f).setConfidence(1.0f),
			(DetectionResponse) new DetectionResponse().setType("face")
				.setBboxX(0.7f).setBboxY(0.1f).setBboxWidth(0.2f).setBboxHeight(0.2f).setConfidence(1.0f));

		assertThat(node().process(NodeContext.create(media, depthOnly()))).isSkipped();

		verify(client, never()).createAssetJsonComp(any(), any());
	}
}
