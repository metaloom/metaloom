package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.openai.OpenAILLMProvider;
import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.llm.LLMNode;
import io.metaloom.cortex.node.llm.LLMNodeOptions;
import io.metaloom.cortex.node.llm.LLMNodePrompt;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code LLMNode} driven against the {@link MockLLMServer}. The node runs its real compute against a real file, but its injected
 * {@link OpenAILLMProvider} points at the OpenAI-compatible mock instead of a live model server. The generated JSON must reach the {@code llm}
 * component (variant = prompt id) and be readable back through REST.
 */
public class LLMNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testLLMPersistsJsonCompViaMockServer() throws Exception {
		JsonObject modelOutput = new JsonObject().put("title", "The Human Readable Title").put("year", "2024");
		try (MockLLMServer llm = MockLLMServer.create(0).addStructuredResponse(modelOutput).start()) {
			withLoom(client -> {
				UniqueAsset ua = createUniqueAsset(client, "video/mp4", "llm node payload".getBytes(StandardCharsets.UTF_8));

				LLMNodeOptions options = new LLMNodeOptions();
				options.setOpenaiUrl(llm.baseUrl());
						LLMNodePrompt prompt = new LLMNodePrompt();
				prompt.setModel("mock-model");
				prompt.setPrompt("Extract metadata from ${name}");
				options.setPrompts(Map.of("default", prompt));

				LLMNode node = new LLMNode(client, cortexOptions(), options, new OpenAILLMProvider());
				NodeResult result = node.process(NodeContext.create(ua.media()));
				assertThat(result.getState().name()).isEqualTo("SUCCESS");

				JsonCompResponse comp = client.listAssetJsonComps(ua.asset().getUuid()).sync().body().getData().stream()
					.filter(c -> "llm".equals(c.getSchemaType()) && "default".equals(c.getVariant()))
					.findFirst().orElse(null);
				assertThat(comp).as("llm JSON component must be readable via REST").isNotNull();
				assertThat(comp.getData().getString("title")).isEqualTo("The Human Readable Title");
			});
		}
	}
}
