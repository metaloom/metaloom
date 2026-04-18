package io.metaloom.cortex.node.facedescription;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel.OllamaChatModelBuilder;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.utils.TextUtils;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.utils.ImageUtils;

public class FacedescriptionNode extends AbstractMediaNode<FacedetectNodeOptions> {

	private static final Logger logger = LoggerFactory.getLogger(FacedescriptionNode.class);

	private static final LargeLanguageModel MODEL = FaceDescriptionModel.OLLAMA_GEMMA3_27B_Q8;

	public static final NodeOutputKey<String> OUTPUT_FACE_DESCRIPTION = NodeOutputKey.of("face_description", String.class);

	public static final String PROMPT = """
		Describe the face. Output only valid JSON without wrapper.
		Example:

		{
		  "nsfw": true|false,
		  "eyes_status": "open|closed",
		  "hair_color":"blue|brown|green|unknown",
		  "mouth_status": "smile|frown|funny|scream|closed|neutral",
		  "face_age": 18,
		  "face_gender":  "male|female",
		  "face_race": "caucasian|black|asian|latin",
		  "face_occluded_by": "",
		  "face_occluded": true|false,
		  "face_frontal_view": true|false,
		  "face_profile_view": true|false,
		}
		""";

	public static final String URL = "http://127.0.0.1:11434";

	private ObjectMapper mapper;

	@Inject
	public FacedescriptionNode(@Nullable LoomClient client, CortexOptions cortexOption, FacedetectNodeOptions options, ObjectMapper mapper) {
		super(client, cortexOption, options);
		this.mapper = mapper;
	}

	@Override
	public String name() {
		return "facedescription";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo() || media.isImage()) {
			return processFaces(ctx);
		} else {
			return ctx.skipped("No visual media").next();
		}
	}

	private NodeResult processFaces(NodeContext<LoomMedia> ctx) {
		Object countObj = ctx.upstreamOutput("facedetect", "face_count");
		if (countObj != null) {
			int count = Integer.parseInt(countObj.toString());
			if (count > 0) {
				// TODO: process individual face thumbnails via upstream output or file paths
				ctx.output(OUTPUT_FACE_DESCRIPTION, "pending");
			}
		}
		return ctx.next();
	}

	public FaceDescription processFace(BufferedImage image) throws IOException {

		OllamaChatModelBuilder builder = OllamaChatModel.builder()
			.baseUrl(URL)
			.timeout(Duration.ofMinutes(15))
			.modelName(MODEL.id())
			.numPredict(2048)
			.temperature(0.6);
		OllamaChatModel chat = builder.build();

		String base64 = ImageUtils.toBase64JPG(image);
		TextContent q = TextContent.from(PROMPT);
		ImageContent img = ImageContent.from(base64, "image/jpeg");
		ChatMessage msg = UserMessage.from(q, img);
		String json = null;
		for (int i = 0; i < 3; i++) {
			try {
				ChatResponse out = chat.chat(msg);
				json = out.aiMessage().text();
				json = TextUtils.extractJson(json);
				FaceDescription description = mapper.readValue(json, FaceDescription.class);
				return description;
			} catch (JsonParseException e1) {
				logger.error("Failed to parse LLM response:\n" + json, e1);
			} catch (Exception e) {
				logger.error("Failed to process image", e);
			}
		}
		return null;
	}
}
