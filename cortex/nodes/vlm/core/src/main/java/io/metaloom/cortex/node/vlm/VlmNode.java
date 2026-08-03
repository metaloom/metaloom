package io.metaloom.cortex.node.vlm;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

/**
 * Runs a vision-language model over an image via an OpenAI-compatible endpoint.
 *
 * <p>
 * The node is generic: each entry in {@link VlmNodeOptions#getPrompts()} is one named task (a model id, a prompt and a response format), so the same node
 * covers document OCR, captioning and ad-hoc extraction. Out of the box it ships the olmOCR preset, which transcribes a document page with
 * {@code allenai/olmOCR-2-7B-1025-FP8} and parses the YAML front matter that model emits.
 * </p>
 *
 * <p>
 * Per prompt the node writes one {@code vlm} JSON component (variant = prompt id) plus an {@code asset_node_result} ledger entry, following the standard
 * two-step node persistence template.
 * </p>
 */
@NodeSpec(nodeId = "vlm", name = "VLM (Vision-Language Model)", icon = "image_search", category = NodeCategory.ANALYSIS,
	description = "Read an image with a vision-language model served over an OpenAI-compatible endpoint. "
		+ "Ships an olmOCR preset for transcribing document pages.",
	dynamicPorts = true)
public class VlmNode extends AbstractMediaNode<VlmNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(VlmNode.class);

	@PortDoc(label = "Image", description = "The page or frame the configured prompts are asked about")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	/** Provider label used for the AI call/cache-hit metrics. */
	private static final String METRICS_PROVIDER = "vlm";

	/** In-heap skip cache of the per-prompt outputs, keyed by media path. Non-durable - the durable copy lives in Loom. */
	private final LocalResultCache<Map<String, String>> resultCache = new LocalResultCache<>(50_000);

	private final VlmChatClient client;

	@Inject
	public VlmNode(@Nullable LoomClient loomClient, CortexOptions cortexOption, VlmNodeOptions options, VlmChatClient client) {
		super(loomClient, cortexOption, options);
		this.client = client;
		if (options.getPrompts().isEmpty()) {
			// A node configured with no prompt would silently do nothing; olmOCR is the reason this node exists, so it is the sensible default.
			options.addPrompt(VlmPromptPresets.OLMOCR_ID, VlmPromptPresets.olmOcr());
		}
	}

	@Override
	public String name() {
		return "vlm";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage();
	}

	/**
	 * The output port carrying a given prompt's result.
	 *
	 * <p>
	 * The id must stay {@code result_<promptId>} - {@code VlmPortResolver} derives the descriptor's
	 * ports from the same {@code prompts} option and produces exactly these names.
	 * </p>
	 */
	public static OutputPort<String> resultPort(String promptId) {
		return OutputPort.one("result_" + promptId, ContentTypeRegistry.TEXT_PLAIN, String.class);
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// On a hit the durable copy already exists in Loom, so re-emit and skip both the model call and the re-persist.
		Map<String, String> cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit(METRICS_PROVIDER);
			cached.forEach((promptId, answer) -> ctx.output(resultPort(promptId), answer));
			return ctx.origin(ResultOrigin.LOCAL).next();
		}

		BufferedImage source = VlmImages.read(media.file());
		Map<String, String> answers = new HashMap<>();

		for (Entry<String, VlmNodePrompt> entry : options().getPrompts().entrySet()) {
			String promptId = entry.getKey();
			VlmNodePrompt prompt = entry.getValue();

			BufferedImage image = VlmImages.downscale(source, prompt.getMaxImageDim());
			VlmReply reply = call(image, prompt);

			JsonObject payload;
			String outputText;
			if (prompt.getResponseFormat() == VlmResponseFormat.OLMOCR) {
				OlmOcrResponse parsed = OlmOcrResponseParser.parse(reply.content()).withTruncated(reply.isTruncated());
				// olmOCR reports a sideways page instead of transcribing it - turn the image and ask once more.
				if (prompt.isRetryOnRotation() && parsed.needsRotation()) {
					ctx.info("olmOCR reported rotation " + parsed.rotationCorrection() + " deg for " + promptId + " - retrying rotated");
					VlmReply retry = call(VlmImages.rotate(image, parsed.rotationCorrection()), prompt);
					parsed = OlmOcrResponseParser.parse(retry.content()).withTruncated(retry.isTruncated());
				}
				if (parsed.truncated()) {
					log.warn("VLM prompt '{}' hit the output token limit for {} - the transcription is cut off, raise maxTokens", promptId, path);
				}
				payload = parsed.toJson();
				outputText = parsed.naturalText();
			} else if (prompt.getResponseFormat() == VlmResponseFormat.JSON) {
				payload = toJsonObject(reply.content());
				outputText = payload.encode();
			} else {
				payload = new JsonObject().put("text", reply.content()).put("truncated", reply.isTruncated());
				outputText = reply.content();
			}

			ctx.output(resultPort(promptId), outputText);
			answers.put(promptId, outputText);
			ctx.info("VLM prompt '" + promptId + "' produced " + outputText.length() + " chars in " + reply.latencyMs() + "ms");
			persist(ctx, asset, promptId, prompt.getModel(), payload);
		}

		resultCache.put(path, answers);
		return ctx.origin(ResultOrigin.COMPUTED).next();
	}

	/**
	 * Issue one model call, recording the AI-call metric on both the success and the failure path.
	 */
	private VlmReply call(BufferedImage image, VlmNodePrompt prompt) throws Exception {
		long start = System.currentTimeMillis();
		try {
			VlmReply reply = client.complete(image, prompt.getPrompt(), prompt.getModel(), prompt.getMaxTokens(), prompt.getTemperature());
			metrics.recordAiCall(METRICS_PROVIDER, true, System.currentTimeMillis() - start);
			return reply;
		} catch (Exception e) {
			metrics.recordAiCall(METRICS_PROVIDER, false, System.currentTimeMillis() - start);
			throw e;
		}
	}

	/**
	 * Parse a reply that should be a JSON object, tolerating the markdown code fence models like to wrap it in. A reply that is not JSON at all is kept as
	 * {@code {"text": ...}} rather than failing the node.
	 */
	private static JsonObject toJsonObject(String content) {
		String text = content == null ? "" : content.strip();
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			try {
				return new JsonObject(text.substring(start, end + 1));
			} catch (RuntimeException e) {
				log.debug("VLM reply looked like JSON but did not parse: {}", e.getMessage());
			}
		}
		return new JsonObject().put("text", text);
	}

	/**
	 * Persist one prompt's result as a {@code vlm} JSON component (variant = prompt id) and record a ledger entry. Best-effort and a no-op when the asset
	 * is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String promptId, String model, JsonObject payload) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("vlm");
			request.setVariant(promptId);
			request.setProducerVersion(model);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, model, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist vlm result '{}' for asset {}: {}", promptId, asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
		}
	}
}
