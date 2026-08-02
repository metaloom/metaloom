package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.vllm.VLLMLLMProvider;
import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.translate.TranslateNode;
import io.metaloom.cortex.node.translate.TranslateNodeOptions;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

/**
 * Integration test for {@code TranslateNode} driven against the {@link MockLLMServer}. The node runs
 * its real compute against a real file and a real Loom, but its injected {@link VLLMLLMProvider}
 * points at the OpenAI-compatible mock instead of a live Ollama backend. The translation must reach
 * the {@code translation} component (variant = target language) and be readable back through REST.
 */
public class TranslateNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String GERMAN = "Der Kundenservice war eine Katastrophe.";

	private static final String ENGLISH = "The customer service was a disaster.";

	private TranslateNodeOptions options(MockLLMServer llm, String targetLanguage) {
		TranslateNodeOptions options = new TranslateNodeOptions()
			.setTargetLanguage(targetLanguage)
			.setSourceLanguage("de")
			.setModel("mock-model");
		options.setOllamaUrl(llm.baseUrl());
		options.setProviderType(LLMProviderType.VLLM);
		return options;
	}

	@Test
	public void testTranslatePersistsJsonCompViaMockServer() throws Exception {
		try (MockLLMServer llm = MockLLMServer.create(0).addResponse(ENGLISH).start()) {
			withLoom(client -> {
				UniqueAsset ua = createUniqueAsset(client, "video/mp4", "translate node payload".getBytes(StandardCharsets.UTF_8));

				TranslateNode node = new TranslateNode(client, cortexOptions(), options(llm, "en"), new VLLMLLMProvider());
				NodeInputs inputs = NodeInputs.builder().input(TranslateNode.IN_TEXT, GERMAN).build();
				NodeResult result = node.process(NodeContext.create(ua.media(), inputs));

				assertThat(result.getState().name()).isEqualTo("SUCCESS");
				assertThat(result.get(TranslateNode.OUT_TRANSLATION)).isEqualTo(ENGLISH);
				assertThat(result.get(TranslateNode.OUT_LANGUAGE)).isEqualTo("en");

				JsonCompResponse comp = client.listAssetJsonComps(ua.asset().getUuid()).sync().body().getData().stream()
					.filter(c -> "translation".equals(c.getSchemaType()) && "en".equals(c.getVariant()))
					.findFirst().orElse(null);
				assertThat(comp).as("translation JSON component must be readable via REST").isNotNull();
				assertThat(comp.getData().getString("text")).isEqualTo(ENGLISH);
				assertThat(comp.getData().getString("targetLanguage")).isEqualTo("en");
				assertThat(comp.getProducerVersion()).isEqualTo("mock-model");
			});
		}
	}

	@Test
	public void testTwoTargetLanguagesCoexistOnOneAsset() throws Exception {
		// This is the whole reason the target language is the component variant rather than a fixed
		// string: two translate nodes in one graph must not overwrite each other.
		String french = "Le service client etait une catastrophe.";
		try (MockLLMServer llm = MockLLMServer.create(0).addResponse(ENGLISH).addResponse(french).start()) {
			withLoom(client -> {
				UniqueAsset ua = createUniqueAsset(client, "video/mp4", "bilingual payload".getBytes(StandardCharsets.UTF_8));
				NodeInputs inputs = NodeInputs.builder().input(TranslateNode.IN_TEXT, GERMAN).build();

				new TranslateNode(client, cortexOptions(), options(llm, "en"), new VLLMLLMProvider())
					.process(NodeContext.create(ua.media(), inputs));
				new TranslateNode(client, cortexOptions(), options(llm, "fr"), new VLLMLLMProvider())
					.process(NodeContext.create(ua.media(), inputs));

				var comps = client.listAssetJsonComps(ua.asset().getUuid()).sync().body().getData().stream()
					.filter(c -> "translation".equals(c.getSchemaType()))
					.toList();

				assertThat(comps).as("both translations must survive side by side").hasSize(2);
				assertThat(comps).extracting(JsonCompResponse::getVariant).containsExactlyInAnyOrder("en", "fr");
			});
		}
	}
}
