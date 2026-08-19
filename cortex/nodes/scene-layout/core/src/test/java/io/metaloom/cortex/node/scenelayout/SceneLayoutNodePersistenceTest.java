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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
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

	private String depthMetaJson() {
		return SceneLayoutFixtures.depthMeta(mapFile, MAP_W, MAP_H, IMAGE_W, IMAGE_H).encode();
	}

	private List<String> twoFaces() {
		return List.of(
			SceneLayoutFixtures.detection(0, "face", 40, 40, 80, 80).encode(),
			SceneLayoutFixtures.detection(1, "face", 280, 40, 80, 80).encode());
	}

	private NodeInputs depthOnly() {
		return NodeInputs.builder()
			.input(SceneLayoutNode.IN_DEPTH, depthMetaJson())
			.build();
	}

	private NodeInputs withUpstreamBoxes() {
		return NodeInputs.builder()
			.input(SceneLayoutNode.IN_DEPTH, depthMetaJson())
			.inputs(SceneLayoutNode.IN_DETECTIONS, twoFaces())
			.build();
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
	void testWritesJsonCompAndLedgerOnSuccess() throws Exception {
		assertThat(node().process(media, withUpstreamBoxes())).isSuccess();

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
	void testRecordsFailedLedgerWhenComponentWriteFails() throws Exception {
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort and deliberately so: persist() catches its own exception,
		// so the node completes and the FAILED row is the record. This is NOT the ctx.failure(...).next()
		// defect - the node never records a failure cause on this path at all, which is why the returned
		// state stays SUCCESS. See testFailedLayoutComputationIsFailedAndKeepsTheCause for the path that
		// does, and the follow-up note in ../../tasks/NODE_TASKS.md for why "green node, unstored
		// result" is worth revisiting on its own terms.
		assertThat(node().process(media, withUpstreamBoxes())).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	/**
	 * A depth map the node cannot read is a failure, and it says which.
	 *
	 * <p>
	 * This is the path that ended in {@code ctx.failure(cause).next()} until 2026-08-18 — SUCCESS with
	 * the cause dropped — so an item whose spatial relations were never computed was reported
	 * identically to one that had none to compute. The trigger is real rather than mocked: the map file
	 * exists (so the node's own existence check passes) but is not a decodable PNG, which is what a
	 * truncated write from the upstream depthmap node leaves behind.
	 * </p>
	 */
	@Test
	void testFailedLayoutComputationIsFailedAndKeepsTheCause() throws Exception {
		java.nio.file.Files.writeString(mapFile.toPath(), "not a png at all");

		assertThat(node().process(media, withUpstreamBoxes()))
			.isFailed()
			.hasNoOutput(SceneLayoutNode.OUT_RESULT);

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())));
	}

	@Test
	void testSkippedRunWritesNothing() throws Exception {
		// No detections anywhere: a skip, and nothing may be persisted for it.
		stubLoomDetections();

		assertThat(node().process(media, depthOnly())).isSkipped();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client, never()).createAssetNodeResult(any(), any());
	}

	@Test
	void testFallsBackToLoomWhenNoUpstreamBoxes() throws Exception {
		stubLoomDetections(
			pixelRow(40, 40, 80, 80),
			pixelRow(280, 40, 80, 80));

		assertThat(node().process(media, depthOnly())).isSuccess();

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

		assertThat(node().process(media, withUpstreamBoxes())).isSuccess();

		// The upstream payload states its coordinate convention, so it is always preferred.
		verify(client, never()).listAssetDetections(any(UUID.class));
	}

	@Test
	void testLoomFallbackCanBeDisabled() throws Exception {
		stubLoomDetections(pixelRow(40, 40, 80, 80), pixelRow(280, 40, 80, 80));

		assertThat(node(new SceneLayoutNodeOptions().setAllowLoomFallback(false))
			.process(media, depthOnly())).isSkipped();

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

		assertThat(node().process(media, depthOnly())).isSkipped();

		verify(client, never()).createAssetJsonComp(any(), any());
	}
}
