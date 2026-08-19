package io.metaloom.cortex.node.sentiment;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
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
 * Sentiment analysis node. Like {@code TtsNode} it does not look at the media
 * itself - it scores the polarity of <em>text produced by an upstream node</em>
 * (Tika content, OCR text, a caption, a VLM or LLM answer) and attaches the
 * result to the asset.
 *
 * <p>
 * The classification runs in the FastAPI {@code /v1/sentiment} sidecar (see
 * {@code sidecars/sentiment}); this node is a pure HTTP client. German routes to
 * german-sentiment-bert, English to twitter-roberta, anything else to a
 * multilingual fallback - the routing, chunking and label normalisation all live
 * in the sidecar so that swapping a checkpoint is configuration, not code.
 * </p>
 *
 * <p>
 * The result is persisted as an {@code asset_json_comp} row with
 * {@code schemaType="sentiment"} and {@code variant} set to this node's kind, so
 * the natural key {@code (asset, node_kind, schema_type, variant)} still holds.
 * The variant used to be the upstream output key, which only worked because the
 * node picked its own text source; now the <em>edge</em> decides where the text
 * comes from.
 * </p>
 */
@NodeSpec(nodeId = "sentiment", name = "Sentiment Analysis", icon = "mood", category = NodeCategory.ANALYSIS,
	description = "Score the polarity (positive/neutral/negative) of text produced by an upstream node. German and English.")
public class SentimentNode extends AbstractMediaNode<SentimentNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SentimentNode.class);

	/**
	 * The text to score.
	 *
	 * <p>
	 * This replaces the {@code textSources} option, an ordered list of
	 * {@code nodeId:outputKey} strings the node walked looking for the first non-blank hit. Every
	 * one of its defaults was stale - {@code llm:llm_result} and {@code vlm:vlm_result} named
	 * outputs those nodes never wrote - so the option's real behaviour was "score the Tika content
	 * or nothing". A declared port makes the source an edge the author draws and validation checks.
	 * </p>
	 */
	@PortDoc(label = "Text", description = "The prose to score - a transcript, caption, OCR result or any other upstream text")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	@PortDoc(label = "Label", description = "The polarity class: positive, neutral or negative")
	public static final OutputPort<String> OUT_LABEL = OutputPort.one("label", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Score", description = "Polarity in [-1, 1]; its sign agrees with the label")
	public static final OutputPort<Double> OUT_SCORE = OutputPort.one("score", ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	@PortDoc(label = "Result", description = "The sidecar's full answer, including the detected language and per-class confidences")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_JSON, String.class);

	/** In-heap skip cache of the scored result JSON, keyed by media path, to avoid re-classifying within this worker's lifetime. Non-durable - the
	 * durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final SentimentClient sentimentClient;

	@Inject
	public SentimentNode(@Nullable LoomClient client, CortexOptions cortexOptions, SentimentNodeOptions options, SentimentClient sentimentClient) {
		super(client, cortexOptions, options);
		this.sentimentClient = sentimentClient;
	}

	@Override
	public String name() {
		return "sentiment";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		// Only processable when the text port was wired and carries something.
		return resolveText(ctx) != null;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		String path = ctx.media().absolutePath();

		String text = resolveText(ctx);
		if (text == null) {
			return ctx.skipped("no upstream text").next();
		}

		// Re-emit a locally cached result instead of re-classifying. On a hit the durable copy already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("sentiment");
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		String model = null;
		try {
			long aiStart = System.currentTimeMillis();
			JsonObject result;
			try {
				result = sentimentClient.analyze(text, options().getLanguage(), modelOverride());
			} catch (RuntimeException e) {
				metrics.recordAiCall("sentiment", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("sentiment", true, System.currentTimeMillis() - aiStart);
			model = result.getString("model");

			JsonObject payload = buildPayload(result, text);
			emit(ctx, payload);
			resultCache.put(path, payload.encode());

			ctx.print("DONE", payload.getString("label") + " (" + payload.getString("lang") + ")");
			persist(ctx, asset, payload, model);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to analyze sentiment for media {}", path, e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Emit the three node outputs from the payload.
	 */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		ctx.output(OUT_LABEL, payload.getString("label"));
		ctx.output(OUT_SCORE, payload.getDouble("score"));
		ctx.output(OUT_RESULT, payload.encode());
	}

	/**
	 * Enrich the sidecar's response with how much text it saw. The result is both the node output
	 * and the persisted component payload.
	 */
	private JsonObject buildPayload(JsonObject result, String text) {
		return result.copy().put("textChars", text.length());
	}

	/**
	 * Persist the scored payload as a {@code sentiment} JSON component and record the ledger entry. Best-effort and a no-op when the asset is not yet
	 * known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload, String model) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("sentiment");
			// One sentiment row per asset per node kind; the edge decides which text produced it.
			request.setVariant("");
			request.setProducerVersion(model);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, model, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist sentiment for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
		}
	}

	/**
	 * Build the optional per-language checkpoint override sent to the sidecar, or null when neither model option is set.
	 */
	private JsonObject modelOverride() {
		JsonObject override = new JsonObject();
		if (options().getModelDe() != null && !options().getModelDe().isBlank()) {
			override.put("de", options().getModelDe());
		}
		if (options().getModelEn() != null && !options().getModelEn().isBlank()) {
			override.put("en", options().getModelEn());
		}
		return override.isEmpty() ? null : override;
	}

	/**
	 * The wired text, truncated to {@code maxChars}, or null when the port carries nothing usable.
	 */
	private String resolveText(NodeContext<LoomMedia> ctx) {
		String text = ctx.input(IN_TEXT);
		if (text == null || text.isBlank()) {
			return null;
		}
		return text.length() > options().getMaxChars() ? text.substring(0, options().getMaxChars()) : text;
	}
}
