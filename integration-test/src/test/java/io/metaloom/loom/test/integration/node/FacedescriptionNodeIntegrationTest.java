package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.ai.genai.llm.openai.OpenAILLMProvider;
import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedescription.FaceDescription;
import io.metaloom.cortex.node.facedescription.FacedescriptionNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code FacedescriptionNode} driven against the {@link MockLLMServer}. Face detection is mocked ({@link InspireFacedetector}) and the
 * per-face vision LLM call ({@code processFace}) is overridden to fetch its description from the OpenAI-compatible mock instead of a live model server. The
 * real image, client, persistence and REST read-back all run: the face description must reach the {@code face-description} component and be readable via REST.
 */
public class FacedescriptionNodeIntegrationTest extends AbstractNodeIntegrationTest {

	/** A FacedescriptionNode whose per-face description is served by the OpenAI-compatible mock LLM server. */
	private static FacedescriptionNode mockBackedNode(LoomClient client, CortexOptions cortexOptions, FacedetectNodeOptions options,
		InspireFacedetector detector, String baseUrl) {
		ObjectMapper mapper = new ObjectMapper();
		return new FacedescriptionNode(client, cortexOptions, options, mapper, detector) {
			@Override
			public FaceDescription processFace(BufferedImage image) {
				LLMContext ctx = LLMContext.ctx(new PromptImpl(FacedescriptionNode.PROMPT),
					new LargeLanguageModelImpl("mock-model", baseUrl, 2048));
				JsonObject json = new OpenAILLMProvider().generateJson(ctx);
				FaceDescription description = new FaceDescription();
				description.setHair(json.getString("hair_color"));
				description.setGender(json.getString("face_gender"));
				return description;
			}
		};
	}

	@Test
	public void testFacedescriptionPersistsJsonCompViaMockServer() throws Exception {
		JsonObject modelOutput = new JsonObject().put("hair_color", "brown").put("face_gender", "female");
		try (MockLLMServer llm = MockLLMServer.create(0).addStructuredResponse(modelOutput).start()) {
			withLoom(client -> {
				AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

				Face face = Face.create(FaceBox.create(0, 0, 20, 20));
				InspireFacedetector detector = mock(InspireFacedetector.class);
				doReturn(List.of(face)).when(detector).detectFaces(any(BufferedImage.class));

				FacedescriptionNode node = mockBackedNode(client, cortexOptions(), new FacedetectNodeOptions(), detector, llm.baseUrl());
				NodeResult result = node.process(NodeContext.create(media(image1())));
				assertThat(result.getState().name()).isEqualTo("SUCCESS");

				JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
					.filter(c -> "face-description".equals(c.getSchemaType()))
					.findFirst().orElse(null);
				assertThat(comp).as("face-description JSON component must be readable via REST").isNotNull();
				assertThat(comp.getData().getJsonArray("faces").size()).isGreaterThanOrEqualTo(1);
			});
		}
	}
}
