package io.metaloom.cortex.node.sentiment;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
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
 * Verifies the two-step persistence contract of {@link SentimentNode}: on success it upserts one {@code asset_json_comp} row
 * ({@code schemaType=sentiment}, {@code variant} = the source output key) and <em>then</em> records an {@code asset_node_result} ledger row pointing
 * at it; on failure it records a FAILED ledger row and writes no component. The sidecar and Loom client are mocked.
 */
class SentimentNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final JsonObject SIDECAR_RESULT = new JsonObject()
		.put("label", "POSITIVE")
		.put("score", 0.94d)
		.put("polarity", 0.91d)
		.put("scores", new JsonObject().put("positive", 0.94d).put("neutral", 0.03d).put("negative", 0.03d))
		.put("lang", "en")
		.put("model", "cardiffnlp/twitter-roberta-base-sentiment-latest")
		.put("chunks", 1)
		.put("truncated", false);

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private SentimentClient sentimentClient;
	private StubLoomMedia media;
	private CortexOptions cortexOptions;

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

		sentimentClient = mock(SentimentClient.class);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "review.txt", "fake-document");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private SentimentNode node() {
		return new SentimentNode(client, cortexOptions, new SentimentNodeOptions(), sentimentClient);
	}

	private NodeContext<LoomMedia> ctxWithText(String text) {
		NodeInputs inputs = NodeInputs.builder().input(SentimentNode.IN_TEXT, text).build();
		return NodeContext.create(media, inputs);
	}

	@Test
	void testWritesJsonCompAndLedgerOnSuccess() {
		when(sentimentClient.analyze(anyString(), anyString(), any())).thenReturn(SIDECAR_RESULT.copy());

		NodeResult result = node().process(ctxWithText("Absolutely delightful stay."));
		assertThat(result).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "sentiment".equals(r.getNodeKind())
			&& "sentiment".equals(r.getSchemaType())
			// There is now one sentiment row per asset per node kind - the edge, not the variant, decides which text produced it.
			&& "".equals(r.getVariant())
			&& "cardiffnlp/twitter-roberta-base-sentiment-latest".equals(r.getProducerVersion())
			&& "POSITIVE".equals(r.getData().getString("label"))));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "sentiment".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testRecordsFailedLedgerWhenSidecarThrows() {
		when(sentimentClient.analyze(anyString(), anyString(), any())).thenThrow(new RuntimeException("sidecar down"));

		node().process(ctxWithText("Absolutely delightful stay."));

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "sidecar down".equals(r.getReason())));
	}

	@Test
	void testRecordsFailedLedgerWhenComponentWriteFails() {
		when(sentimentClient.analyze(anyString(), anyString(), any())).thenReturn(SIDECAR_RESULT.copy());
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still succeeds, but the ledger records the failure.
		node().process(ctxWithText("Absolutely delightful stay."));

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}
}
