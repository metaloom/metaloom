package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.ai.genai.llm.vllm.VLLMLLMProvider;
import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.node.captioning.CaptioningNode;
import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;
import io.metaloom.cortex.node.captioning.SmolVLMClient;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

/**
 * Integration test for {@code CaptioningNode} driven against the {@link MockLLMServer}. The node runs its real image-loading + persistence path against a
 * real image, but its injected {@link SmolVLMClient} is replaced with one that fetches the caption from the OpenAI-compatible mock instead of a live SmolVLM
 * server. The caption must reach the {@code caption} component and be readable back through REST.
 */
public class CaptioningNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String CAPTION = "A mock caption describing the image.";

	/** A SmolVLMClient whose captioning is served by the OpenAI-compatible mock LLM server. */
	private static SmolVLMClient mockBackedClient(String baseUrl) {
		return new SmolVLMClient("localhost", 0) {
			@Override
			public String captionByImage(BufferedImage image, int targetSize) {
				LLMContext ctx = LLMContext.ctx(new PromptImpl("Caption this image"),
					new LargeLanguageModelImpl("mock-model", baseUrl, 2048, LLMProviderType.VLLM));
				return new VLLMLLMProvider().generate(ctx);
			}
		};
	}

	@Test
	public void testCaptioningPersistsJsonCompViaMockServer() throws Exception {
		try (MockLLMServer llm = MockLLMServer.create(0).addResponse(CAPTION).start()) {
			withLoom(client -> {
				assumeVideo4j();
				AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

				CaptioningNode node = new CaptioningNode(client, cortexOptions(), new CaptioningNodeOptions(), mockBackedClient(llm.baseUrl()));
				NodeResult result = node.process(NodeContext.create(media(image1())));
				assertThat(result.getState().name()).isEqualTo("SUCCESS");

				JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
					.filter(c -> "caption".equals(c.getSchemaType()))
					.findFirst().orElse(null);
				assertThat(comp).as("caption JSON component must be readable via REST").isNotNull();
				assertThat(comp.getData().getString("caption")).isEqualTo(CAPTION);
			});
		}
	}
}
