package io.metaloom.cortex.node.sentiment;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
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
 * {@code schemaType="sentiment"} and {@code variant} set to the output key the
 * text came from. Because the natural key is
 * {@code (asset, node_kind, schema_type, variant)}, an asset can carry one
 * sentiment row per text source without collision - the same use of
 * {@code variant} that {@code LLMNode} makes for its prompt id.
 * </p>
 */
public class SentimentNode extends AbstractMediaNode<SentimentNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SentimentNode.class);

	public static final NodeOutputKey<String> OUTPUT_SENTIMENT_LABEL = NodeOutputKey.of("sentiment_label", String.class);
	public static final NodeOutputKey<Double> OUTPUT_SENTIMENT_SCORE = NodeOutputKey.of("sentiment_score", Double.class);
	public static final NodeOutputKey<String> OUTPUT_SENTIMENT_RESULT = NodeOutputKey.of("sentiment_result", String.class);

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
		// Only processable when an upstream node supplied text to score.
		return resolveText(ctx) != null;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		String path = ctx.media().absolutePath();

		TextSource source = resolveText(ctx);
		if (source == null) {
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
				result = sentimentClient.analyze(source.text(), options().getLanguage(), modelOverride());
			} catch (RuntimeException e) {
				metrics.recordAiCall("sentiment", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("sentiment", true, System.currentTimeMillis() - aiStart);
			model = result.getString("model");

			JsonObject payload = buildPayload(result, source);
			emit(ctx, payload);
			resultCache.put(path, payload.encode());

			ctx.print("DONE", payload.getString("label") + " (" + payload.getString("lang") + ")");
			persist(ctx, asset, source, payload, model);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to analyze sentiment for media {}", path, e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Emit the three node outputs from the payload.
	 */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		ctx.output(OUTPUT_SENTIMENT_LABEL, payload.getString("label"));
		ctx.output(OUTPUT_SENTIMENT_SCORE, payload.getDouble("score"));
		ctx.output(OUTPUT_SENTIMENT_RESULT, payload.encode());
	}

	/**
	 * Enrich the sidecar's response with the text source it was computed from. The result is both the node output and the persisted component payload.
	 */
	private JsonObject buildPayload(JsonObject result, TextSource source) {
		return result.copy()
			.put("source", new JsonObject()
				.put("nodeId", source.nodeId())
				.put("outputKey", source.outputKey()))
			.put("textChars", source.text().length());
	}

	/**
	 * Persist the scored payload as a {@code sentiment} JSON component and record the ledger entry. Best-effort and a no-op when the asset is not yet
	 * known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, TextSource source, JsonObject payload, String model) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("sentiment");
			// The source output key discriminates several text sources on the same asset - the natural key includes the variant.
			request.setVariant(source.outputKey());
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
	 * Walk the configured {@code nodeId:outputKey} sources in order and return the first that yields non-blank text, or null when none does.
	 */
	private TextSource resolveText(NodeContext<LoomMedia> ctx) {
		for (String entry : options().getTextSources()) {
			if (entry == null) {
				continue;
			}
			int idx = entry.indexOf(':');
			if (idx <= 0 || idx == entry.length() - 1) {
				continue;
			}
			String nodeId = entry.substring(0, idx);
			String outputKey = entry.substring(idx + 1);
			Object value = ctx.upstreamOutput(nodeId, outputKey);
			if (value == null) {
				continue;
			}
			String text = value.toString();
			if (text.isBlank()) {
				continue;
			}
			if (text.length() > options().getMaxChars()) {
				text = text.substring(0, options().getMaxChars());
			}
			return new TextSource(nodeId, outputKey, text);
		}
		return null;
	}

	/**
	 * The upstream output that supplied the analysed text.
	 *
	 * @param nodeId    the upstream node id
	 * @param outputKey the upstream output key - also the persisted component's variant
	 * @param text      the (possibly truncated) text
	 */
	private record TextSource(String nodeId, String outputKey, String text) {
	}
}
