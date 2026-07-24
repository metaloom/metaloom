package io.metaloom.cortex.node.facedescription;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
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
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.utils.ImageUtils;

public class FacedescriptionNode extends AbstractMediaNode<FacedetectNodeOptions> {

	private static final Logger logger = LoggerFactory.getLogger(FacedescriptionNode.class);

	private static final LargeLanguageModel MODEL = FaceDescriptionModel.OLLAMA_GEMMA3_27B_Q8;

	public static final NodeOutputKey<String> OUTPUT_FACE_DESCRIPTION = NodeOutputKey.of("face_description", String.class);

	/** In-heap skip cache of the per-face description JSON, keyed by media path, to avoid re-running the vision LLM within this worker's lifetime.
	 * Non-durable - the durable copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(50_000);

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

	private final ObjectMapper mapper;
	private final InspireFacedetector inspireface;

	@Inject
	public FacedescriptionNode(@Nullable LoomClient client, CortexOptions cortexOption, FacedetectNodeOptions options,
			ObjectMapper mapper, @Nullable InspireFacedetector inspireface) {
		super(client, cortexOption, options);
		this.mapper = mapper;
		this.inspireface = inspireface;
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
			return processFaces(ctx, asset);
		} else {
			return ctx.skipped("No visual media").next();
		}
	}

	/**
	 * Describe every face detected on the media asset. Faces are re-detected
	 * from the source image (image media only for now) using the same
	 * {@link InspireFacedetector} that {@code FacedetectNode} uses, cropped
	 * from the bounding box, and each thumbnail is fed into the vision LLM.
	 * The collected descriptions are emitted as a JSON array under the
	 * {@code face_description} output key.
	 */
	private NodeResult processFaces(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		String path = ctx.media().absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUTPUT_FACE_DESCRIPTION, cached);
			return ctx.origin(io.metaloom.cortex.api.node.ResultOrigin.LOCAL).next();
		}

		Object countObj = ctx.upstreamOutput("facedetect", "face_count");
		int upstreamCount = countObj != null ? Integer.parseInt(countObj.toString()) : -1;
		if (upstreamCount == 0) {
			return ctx.next();
		}

		LoomMedia media = ctx.media();
		if (!media.isImage()) {
			// Video face description would require per-frame extraction which
			// is a larger scope; skip cleanly for now instead of emitting a
			// bogus placeholder.
			return ctx.skipped("Video face description not yet supported").next();
		}

		if (inspireface == null) {
			return ctx.skipped("InspireFacedetector not configured").next();
		}

		BufferedImage image = ImageIO.read(media.file());
		if (image == null) {
			return ctx.skipped("Unable to read image").next();
		}

		List<? extends Face> faces = inspireface.detectFaces(image);
		int count = faces == null ? 0 : faces.size();
		if (count == 0) {
			return ctx.next();
		}

		List<FaceDescription> descriptions = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Face face = faces.get(i);
			BufferedImage crop = cropFace(image, face.box());
			if (crop == null) {
				continue;
			}
			try {
				FaceDescription desc = processFace(crop);
				if (desc != null) {
					descriptions.add(desc);
				}
			} catch (Exception e) {
				logger.warn("Failed to describe face {}/{} for {}", i + 1, count, media.absolutePath(), e);
			}
		}

		String json = mapper.writeValueAsString(descriptions);
		ctx.output(OUTPUT_FACE_DESCRIPTION, json);
		resultCache.put(path, json);
		persist(ctx, asset, json);
		return ctx.next();
	}

	/**
	 * Persist the per-face descriptions as a {@code face-description} JSON component and record a ledger entry. Best-effort and a no-op when the asset
	 * is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String facesJson) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("face-description");
			request.setVariant("");
			request.setData(new JsonObject().put("faces", new JsonArray(facesJson)));
			java.util.UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, MODEL.id(), resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			logger.warn("Failed to persist face descriptions for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), MODEL.id(), null);
		}
	}

	/**
	 * Crop a face region from the source image, clamped to the image bounds.
	 * Returns {@code null} for a degenerate crop (zero or negative area).
	 */
	private static BufferedImage cropFace(BufferedImage image, FaceBox box) {
		int x = Math.max(0, box.getStartX());
		int y = Math.max(0, box.getStartY());
		int w = Math.min(box.getWidth(), image.getWidth() - x);
		int h = Math.min(box.getHeight(), image.getHeight() - y);
		if (w <= 0 || h <= 0) {
			return null;
		}
		return image.getSubimage(x, y, w, h);
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

