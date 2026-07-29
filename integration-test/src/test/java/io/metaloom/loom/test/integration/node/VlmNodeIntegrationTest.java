package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.vlm.VlmChatClient;
import io.metaloom.cortex.node.vlm.VlmNode;
import io.metaloom.cortex.node.vlm.VlmNodeOptions;
import io.metaloom.cortex.node.vlm.VlmPromptPresets;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * Integration test for {@code VlmNode} driven against the {@link MockLLMServer}, which speaks the same OpenAI-compatible {@code /v1/chat/completions}
 * protocol as vLLM. The node builds and sends the real multimodal request for a real image file, but the answer comes from the mock instead of a GPU. The
 * file, the {@link io.metaloom.loom.client.http.LoomHttpClient} and the Loom backend are all real: the parsed olmOCR payload must reach the {@code vlm}
 * JSON component and be readable back through REST, and the ledger row must point at it.
 */
public class VlmNodeIntegrationTest extends AbstractNodeIntegrationTest {

	/** A representative olmOCR page reply: YAML front matter, then the transcribed page. */
	private static final String OLMOCR_REPLY = """
		---
		primary_language: en
		is_rotation_valid: True
		rotation_correction: 0
		is_table: False
		is_diagram: False
		---
		Integration test page body.
		""";

	private VlmNode node(io.metaloom.loom.client.http.LoomHttpClient client, MockLLMServer server) {
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl(server.baseUrl())
			.addPrompt(VlmPromptPresets.OLMOCR_ID, VlmPromptPresets.olmOcr("mock-model"));
		return new VlmNode(client, cortexOptions(), options, new VlmChatClient(server.baseUrl(), null));
	}

	@Test
	public void testVlmPersistsJsonCompAndLedger() throws Exception {
		try (MockLLMServer server = MockLLMServer.create(0).addResponse(OLMOCR_REPLY).start()) {
			withLoom(client -> {
				AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

				NodeResult result = node(client, server).process(NodeContext.create(media(image1())));
				assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
				assertThat(result.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)))
					.as("the node must emit the transcribed page text")
					.contains("Integration test page body.");

				JsonCompResponse comp = jsonComp(client, asset);
				assertThat(comp).as("vlm JSON component must be readable via REST").isNotNull();
				assertThat(comp.getNodeKind()).isEqualTo("vlm");
				assertThat(comp.getData().getString("natural_text")).contains("Integration test page body.");
				assertThat(comp.getData().getString("primary_language")).isEqualTo("en");
				assertThat(comp.getData().getBoolean("is_rotation_valid")).isTrue();
				assertThat(comp.getData().getBoolean("is_table")).isFalse();

				NodeResultResponse ledger = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
					.filter(r -> "vlm".equals(r.getNodeKind()))
					.findFirst().orElse(null);
				assertThat(ledger).as("vlm node-result ledger row must be readable via REST").isNotNull();
				assertThat(ledger.getState()).isEqualTo("SUCCESS");
				assertThat(ledger.getProducerVersion()).isEqualTo("mock-model");
				assertThat(ledger.getResultRef().getString("table")).isEqualTo("asset_json_comp");
				assertThat(ledger.getResultRef().getJsonArray("uuids").getList())
					.as("the ledger must point at the component the node wrote")
					.containsExactly(comp.getUuid().toString());
			});
		}
	}

	/**
	 * The component is keyed by (asset, node_kind, schema_type, variant), so a re-run must rewrite its own row rather than accumulate duplicates. Each run
	 * needs its own node instance - a single instance would serve the second pass from its in-heap cache and never reach Loom.
	 */
	@Test
	public void testRerunUpsertsInsteadOfDuplicating() throws Exception {
		try (MockLLMServer server = MockLLMServer.create(0).addResponse(OLMOCR_REPLY).addResponse(OLMOCR_REPLY.replace(
			"Integration test page body.", "Second pass body.")).start()) {
			withLoom(client -> {
				AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

				assertThat(node(client, server).process(NodeContext.create(media(image1()))).getState()).isEqualTo(ResultState.SUCCESS);
				assertThat(node(client, server).process(NodeContext.create(media(image1()))).getState()).isEqualTo(ResultState.SUCCESS);

				List<JsonCompResponse> comps = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
					.filter(c -> "vlm".equals(c.getSchemaType()) && VlmPromptPresets.OLMOCR_ID.equals(c.getVariant()))
					.toList();
				assertThat(comps).as("a re-run must upsert its own row, not add a second one").hasSize(1);
				assertThat(comps.get(0).getData().getString("natural_text")).contains("Second pass body.");
			});
		}
	}

	private JsonCompResponse jsonComp(io.metaloom.loom.client.http.LoomHttpClient client, AssetResponse asset) throws Exception {
		return client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
			.filter(c -> "vlm".equals(c.getSchemaType()) && VlmPromptPresets.OLMOCR_ID.equals(c.getVariant()))
			.findFirst().orElse(null);
	}
}
