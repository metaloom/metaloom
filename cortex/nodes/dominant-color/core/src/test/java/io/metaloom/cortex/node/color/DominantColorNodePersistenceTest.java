package io.metaloom.cortex.node.color;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Verifies the two-step persistence contract of {@link DominantColorNode}: on success it upserts one
 * {@code asset_json_comp} row ({@code schemaType=dominant-color}) and <em>then</em> records an
 * {@code asset_node_result} ledger row pointing at it; on failure it records a FAILED ledger row and
 * writes no component; on a skip it writes neither.
 */
class DominantColorNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;

	private static final String RED = "#FF0000";
	private static final String BLUE = "#0000FF";

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private CortexOptions cortexOptions;
	private StubLoomMedia media;

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

		File file = DominantColorFixtures.writeSplit(tempDir, "split.png", RED, BLUE, 0.5d, IMAGE_W, IMAGE_H);
		media = new StubLoomMedia(file.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	@Test
	void testWritesJsonCompAndLedgerOnSuccess() {
		assertThat(node().process(ctxWithBoxes())).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "dominant-color".equals(r.getNodeKind())
			&& "dominant-color".equals(r.getSchemaType())
			// One row per asset - every region lives inside the payload. Reserved for a frame
			// number once video lands.
			&& "".equals(r.getVariant())
			&& r.getData().getJsonArray("regions").size() == 3));

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "dominant-color".equals(r.getNodeKind())
				&& "SUCCESS".equals(r.getState())
				&& "COMPUTED".equals(r.getOrigin())
				&& r.getResultRef() != null
				&& "asset_json_comp".equals(r.getResultRef().getString("table"))
				&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	/**
	 * The ledger row must be written after the component, so its {@code result_ref} can point at a
	 * uuid that already exists.
	 */
	@Test
	void testTheLedgerFollowsTheComponent() {
		assertThat(node().process(ctxWithBoxes())).isSuccess();

		InOrder order = inOrder(client);
		order.verify(client).createAssetJsonComp(any(), any());
		order.verify(client).createAssetNodeResult(any(), any());
	}

	/**
	 * There is no model to name, but the term codebook and the modifier table decide every emitted
	 * name, so a retune of either must be visible on already-processed assets. This assertion is
	 * what forces {@link DominantColorNode#ALGORITHM_VERSION} to be bumped when they change.
	 */
	@Test
	void testTheAlgorithmVersionIsRecordedAsTheProducerVersion() {
		assertThat(node().process(ctxWithBoxes())).isSuccess();

		verify(client).createAssetJsonComp(any(),
			argThat((JsonCompCreateRequest r) -> "dominant-color/1".equals(r.getProducerVersion())));
		verify(client).createAssetNodeResult(any(),
			argThat((NodeResultCreateRequest r) -> "dominant-color/1".equals(r.getProducerVersion())));
	}

	@Test
	void testThePersistedPayloadCarriesTheNamesAndTheGeometry() {
		assertThat(node().process(ctxWithBoxes())).isSuccess();

		verify(client).createAssetJsonComp(any(), argThat((JsonCompCreateRequest r) -> {
			JsonObject whole = r.getData().getJsonArray("regions").getJsonObject(0);
			JsonObject face = r.getData().getJsonArray("regions").getJsonObject(1);
			JsonObject name = face.getJsonObject("dominant").getJsonObject("name");
			return "whole".equals(whole.getString("id"))
				&& "face-0".equals(face.getString("id"))
				&& RED.equals(face.getJsonObject("dominant").getString("hex"))
				&& "red".equals(name.getString("term"))
				&& "kräftiges Rot".equals(name.getString("de"))
				&& face.getJsonObject("bbox").getInteger("w") == 100;
		}));
	}

	@Test
	void testRecordsFailedLedgerWhenComponentWriteFails() {
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still completes, but the ledger records it.
		assertThat(node().process(ctxWithBoxes())).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())
				&& "loom unreachable".equals(r.getReason())
				&& r.getResultRef() == null));
	}

	@Test
	void testSkippedRunWritesNothing() throws Exception {
		File ghost = DominantColorFixtures.writeFullyTransparent(tempDir, "ghost.png", RED, IMAGE_W, IMAGE_H);
		StubLoomMedia transparent = new StubLoomMedia(ghost.getAbsolutePath(), false, true, false, false);
		transparent.setSHA512(HASH);

		assertThat(node().process(NodeContext.create(transparent, Map.of()))).isSkipped();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client, never()).createAssetNodeResult(any(), any());
	}

	@Test
	void testAnUndecodableImageRecordsAFailedLedgerAndNoComponent() throws Exception {
		File broken = new File(tempDir, "broken.png");
		java.nio.file.Files.write(broken.toPath(), "not a png at all".getBytes());
		StubLoomMedia corrupt = new StubLoomMedia(broken.getAbsolutePath(), false, true, false, false);
		corrupt.setSHA512(HASH);

		assertThat(node().process(NodeContext.create(corrupt, Map.of()))).isFailed();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())));
	}

	/**
	 * A cache hit must skip re-persistence as well as re-computation - the durable copy is already
	 * in Loom.
	 */
	@Test
	void testACachedRunDoesNotWriteAgain() {
		DominantColorNode node = node();

		assertThat(node.process(ctxWithBoxes())).isSuccess();
		assertThat(node.process(ctxWithBoxes())).isSuccess();

		verify(client, times(1)).createAssetJsonComp(any(), any());
		verify(client, times(1)).createAssetNodeResult(any(), any());
	}

	private DominantColorNode node() {
		return new DominantColorNode(client, cortexOptions, new DominantColorNodeOptions());
	}

	private NodeContext<LoomMedia> ctxWithBoxes() {
		Map<String, Map<String, Object>> up = new HashMap<>();
		up.put("facedetect", Map.of("detections", DominantColorFixtures.detections(IMAGE_W, IMAGE_H,
			DominantColorNodeOptions.ABSOLUTE_PIXELS,
			DominantColorFixtures.detection(0, "face", 20, 20, 100, 100),
			DominantColorFixtures.detection(1, "face", 260, 20, 100, 100)).encode()));
		return NodeContext.create(media, up);
	}
}
