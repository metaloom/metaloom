package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

import javax.inject.Provider;

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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What reaches Loom: one {@code asset_json_comp} carrying the routing decision, and one
 * {@code asset_node_result} ledger row pointing at it.
 *
 * <p>
 * The {@code variant} is the <em>pipeline node id</em>, not the kind. Two filter nodes over one asset
 * is the normal case — route by language, then by genre — and a shared variant would have the second
 * overwrite the first.
 * </p>
 */
class FilterNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
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

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.txt", "some text");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private FilterNode node(StubLLMProvider provider, String nodeId) {
		Provider<FilterStrategy> strategy = () -> new LanguageFilterStrategy(provider);
		FilterNode node = new FilterNode(client, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.LANGUAGE, strategy));
		node.configure(new JsonObject()
			.put("id", nodeId)
			.put("filterBy", "LANGUAGE")
			.put("buckets", new JsonArray().add(new JsonObject().put("id", "de").put("label", "German"))));
		return node;
	}

	private NodeResult run(FilterNode node) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media, NodeInputs.builder().input(FilterNode.IN_TEXT, "Guten Tag").build());
		return node.process(ctx);
	}

	@Test
	void testWritesTheDecisionAsAComponentAndRecordsTheLedgerRow() {
		assertThat(run(node(StubLLMProvider.answering("de"), "lang"))).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "filter".equals(r.getNodeKind())
			&& "filter".equals(r.getSchemaType())
			// The pipeline node id, so two filter instances coexist on one asset.
			&& "lang".equals(r.getVariant())
			&& "de".equals(r.getData().getString("bucket"))
			&& "LANGUAGE".equals(r.getData().getString("filterBy"))));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "filter".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			// A typed component was written, so the ledger row must point at it.
			&& r.getResultRef() != null));
	}

	@Test
	void testTheLedgerNodeIdIsScopedPerPipelineNode() {
		assertThat(run(node(StubLLMProvider.answering("de"), "by-language"))).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "filter:by-language".equals(r.getNodeId())));
	}

	/**
	 * A failure has to leave a FAILED row and no component. Recording success for a task that threw
	 * is how a broken model quietly looks like a pipeline that classified everything as {@code other}.
	 */
	@Test
	void testRecordsAFailedRowWhenTheModelIsUnreachable() {
		NodeResult result = run(node(StubLLMProvider.failing("connection refused"), "lang"));

		assertThat(result).isFailed();
		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "filter".equals(r.getNodeKind())));
	}

	/**
	 * A cache hit must not re-persist: the durable copy is already in Loom, and writing it again per
	 * run would make the ledger a record of how often we looked rather than of what was decided.
	 */
	@Test
	void testACacheHitDoesNotWriteAgain() {
		FilterNode node = node(StubLLMProvider.answering("de"), "lang");

		assertThat(run(node)).isSuccess();
		assertThat(run(node)).isSuccess();

		verify(client, org.mockito.Mockito.times(1)).createAssetJsonComp(any(), any());
		verify(client, org.mockito.Mockito.times(1)).createAssetNodeResult(any(), any());
	}

	@Test
	void testTheProducerVersionIsRecorded() {
		FilterNode node = node(StubLLMProvider.answering("de"), "lang");
		String expected = node.producerVersion();
		assertThat(run(node)).isSuccess();

		assertEquals("filter/1:LANGUAGE:", expected.substring(0, expected.lastIndexOf(':') + 1));
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> expected.equals(r.getProducerVersion())));
	}
}
