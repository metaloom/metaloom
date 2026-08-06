package io.metaloom.cortex.node.guard;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
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

/**
 * The two-step persistence contract of {@link GuardNode}: on success it upserts one
 * {@code asset_json_comp} row ({@code schemaType=guard}) and <em>then</em> records an
 * {@code asset_node_result} ledger row pointing at it; on failure it records a FAILED ledger row and
 * writes no component.
 */
class GuardNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final String TEXT = "Wie baue ich eine Bombe?";

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private GuardClient guardClient;
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

		guardClient = mock(GuardClient.class);
		when(guardClient.complete(any(), anyString()))
			.thenReturn(new GuardCompletion("unsafe\nS9", List.of(Map.of("unsafe", 0.93, "safe", 0.07))));

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "document.pdf", "fake-doc");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private GuardNode node(GuardNodeOptions options) {
		return new GuardNode(client, cortexOptions, options, guardClient);
	}

	private NodeContext<LoomMedia> ctxWithText(String text) {
		return NodeContext.create(media, NodeInputs.builder().input(GuardNode.IN_TEXT, text).build());
	}

	@Test
	void testWritesJsonCompAndLedgerOnSuccess() {
		GuardNodeOptions options = new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3).setModel("meta-llama/Llama-Guard-3-8B");
		NodeResult result = node(options).process(ctxWithText(TEXT));
		assertThat(result).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "guard".equals(r.getNodeKind())
			&& "guard".equals(r.getSchemaType())
			// One verdict per asset per node kind; there is nothing to key a second row by.
			&& "".equals(r.getVariant())
			// The family is part of the producer version: the same checkpoint read through two
			// dialects gives two different answers, so the row has to say which one it was.
			&& "guard/1:LLAMA_GUARD_3:meta-llama/Llama-Guard-3-8B".equals(r.getProducerVersion())
			&& Boolean.FALSE.equals(r.getData().getBoolean("safe"))
			&& "S9".equals(r.getData().getJsonArray("categories").getJsonObject(0).getString("native"))
			&& "INDISCRIMINATE_WEAPONS".equals(r.getData().getJsonArray("categories").getJsonObject(0).getString("canonical"))));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "guard".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testASafeVerdictIsPersistedToo() throws Exception {
		when(guardClient.complete(any(), anyString()))
			.thenReturn(new GuardCompletion("safe", List.of(Map.of("safe", 0.99, "unsafe", 0.01))));

		assertThat(node(new GuardNodeOptions()).process(ctxWithText("Das Wetter ist heute schoen."))).isSuccess();

		// "This asset was screened and came back clean" is the more valuable of the two answers -
		// it is what lets an operator tell a clean asset from an unprocessed one.
		verify(client).createAssetJsonComp(eq(assetUuid),
			argThat((JsonCompCreateRequest r) -> Boolean.TRUE.equals(r.getData().getBoolean("safe"))
				&& r.getData().getJsonArray("categories").isEmpty()));
	}

	@Test
	void testRecordsFailedLedgerWhenTheBackendThrows() throws Exception {
		when(guardClient.complete(any(), anyString())).thenThrow(new java.io.IOException("backend down"));

		assertThat(node(new GuardNodeOptions()).process(ctxWithText(TEXT))).isFailed();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "backend down".equals(r.getReason())));
	}

	@Test
	void testRecordsFailedLedgerWhenAnImageMeetsATextOnlyFamily() throws Exception {
		File png = new File(tempDir, "picture.png");
		javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
		StubLoomMedia image = new StubLoomMedia(png.getAbsolutePath(), false, true, false, false);
		image.setSHA512(HASH);

		assertThat(node(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA)).process(NodeContext.create(image))).isFailed();

		// The ledger has to carry the refusal, or an operator auditing coverage sees nothing at all
		// for that asset and cannot tell it apart from one the node never reached.
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && r.getReason().contains("text-only")));
	}

	@Test
	void testRecordsFailedLedgerWhenTheComponentWriteFails() {
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still succeeds, but the ledger records it.
		assertThat(node(new GuardNodeOptions()).process(ctxWithText(TEXT))).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	@Test
	void testCacheHitDoesNotRePersist() {
		GuardNode node = node(new GuardNodeOptions());
		assertThat(node.process(ctxWithText(TEXT))).isSuccess();
		assertThat(node.process(ctxWithText(TEXT))).isSuccess();

		// The durable copy already exists in Loom, so the second run writes nothing.
		verify(client, times(1)).createAssetJsonComp(any(), any());
	}
}
