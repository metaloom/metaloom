package io.metaloom.cortex.node.translate;

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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
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
 * Verifies the two-step persistence contract of {@link TranslateNode}: on success it upserts one
 * {@code asset_json_comp} row ({@code schemaType=translation}, {@code variant} = the target
 * language) and <em>then</em> records an {@code asset_node_result} ledger row pointing at it; on
 * failure it records a FAILED ledger row and writes no component.
 *
 * <p>
 * The variant is what lets two translate nodes coexist on one asset, so it is asserted explicitly
 * rather than left to the default.
 * </p>
 */
class TranslateNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final String GERMAN = "Der Kundenservice war eine Katastrophe.";

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private LLMProvider provider;
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

		provider = mock(LLMProvider.class);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "interview.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private TranslateNode node(TranslateNodeOptions options) {
		return new TranslateNode(client, cortexOptions, options, provider);
	}

	private NodeContext<LoomMedia> ctxWithText(String text) {
		NodeInputs inputs = NodeInputs.builder().input(TranslateNode.IN_TEXT, text).build();
		return NodeContext.create(media, inputs);
	}

	@Test
	void testWritesJsonCompAndLedgerOnSuccess() {
		when(provider.generate(any(LLMContext.class))).thenReturn("The customer service was a disaster.");

		TranslateNodeOptions options = new TranslateNodeOptions().setTargetLanguage("en").setModel("google/gemma-2-27b-it");
		NodeResult result = node(options).process(ctxWithText(GERMAN));
		assertThat(result).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "translate".equals(r.getNodeKind())
			&& "translation".equals(r.getSchemaType())
			// The target language is the variant, so en/de/fr rows coexist on one asset.
			&& "en".equals(r.getVariant())
			&& "google/gemma-2-27b-it".equals(r.getProducerVersion())
			&& "The customer service was a disaster.".equals(r.getData().getString("text"))
			&& "en".equals(r.getData().getString("targetLanguage"))));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "translate".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testVariantFollowsTheTargetLanguage() {
		when(provider.generate(any(LLMContext.class))).thenReturn("Le service client était une catastrophe.");

		assertThat(node(new TranslateNodeOptions().setTargetLanguage("fr")).process(ctxWithText(GERMAN))).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid),
			argThat((JsonCompCreateRequest r) -> "fr".equals(r.getVariant()) && "fr".equals(r.getData().getString("targetLanguage"))));
	}

	@Test
	void testRecordsFailedLedgerWhenModelThrows() {
		when(provider.generate(any(LLMContext.class))).thenThrow(new RuntimeException("backend down"));

		NodeResult result = node(new TranslateNodeOptions()).process(ctxWithText(GERMAN));
		assertThat(result).isFailed();

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "backend down".equals(r.getReason())));
	}

	@Test
	void testRecordsFailedLedgerWhenComponentWriteFails() {
		when(provider.generate(any(LLMContext.class))).thenReturn("The customer service was a disaster.");
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still succeeds, but the ledger records the failure.
		assertThat(node(new TranslateNodeOptions()).process(ctxWithText(GERMAN))).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	@Test
	void testCacheHitDoesNotRePersist() {
		when(provider.generate(any(LLMContext.class))).thenReturn("The customer service was a disaster.");

		TranslateNode node = node(new TranslateNodeOptions());
		assertThat(node.process(ctxWithText(GERMAN))).isSuccess();
		assertThat(node.process(ctxWithText(GERMAN))).isSuccess();

		// The durable copy already exists in Loom, so the second run writes nothing.
		verify(client, org.mockito.Mockito.times(1)).createAssetJsonComp(any(), any());
	}
}
