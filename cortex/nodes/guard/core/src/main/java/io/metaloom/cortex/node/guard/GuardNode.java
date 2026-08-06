package io.metaloom.cortex.node.guard;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Content safety classification with an open guardrail model.
 *
 * <p>
 * One node kind covering three model families — Meta Llama Guard 3/4, Google ShieldGemma 1/2 and IBM
 * Granite Guardian 3.x — so an operator swaps the model without rewiring the graph. Everything
 * family-specific lives behind {@link GuardDialect}; everything here is written against the
 * normalised {@link GuardVerdict}, whose {@code score} is always P(unsafe) and whose categories are
 * always in the shared {@link GuardCategory} vocabulary.
 * </p>
 *
 * <p>
 * The node classifies text, pixels, or both. {@link #IN_TEXT} takes prose from any upstream
 * extractor — a Whisper transcript, Tika document text, an OCR result, a caption — and
 * {@link #IN_MEDIA} is the item's own pixels, classified whenever the asset is an image and the
 * configured model can read one. Having both classifies both and reports the worse of the two,
 * which is the useful reading for a screening gate.
 * </p>
 *
 * <p>
 * <strong>The image path needs vLLM.</strong> Only Llama Guard 4 and ShieldGemma 2 look at pixels at
 * all, and neither can be served by llama.cpp today: no {@code mmproj} projector has been published
 * for Llama Guard 4, and {@code shieldgemma-2-4b} has no GGUF conversion. An image asset reaching a
 * text-only family with nothing on the text port <em>fails</em> rather than passing — a content
 * guard that quietly waves through everything it cannot read is worse than one that stops.
 * </p>
 *
 * <p>
 * {@link #OUT_SAFE} is a {@code control/filter} port, so the guard gates a branch directly with no
 * filter node in between: a node wired behind it is skipped for every item that was flagged. The
 * verdict is also persisted as an {@code asset_json_comp} row with {@code schemaType="guard"}.
 * </p>
 */
@NodeSpec(nodeId = "guard", name = "Content Guard", icon = "shield", category = NodeCategory.ANALYSIS,
	description = "Classify text or images against a harm taxonomy with a guardrail model (Llama Guard, ShieldGemma, Granite Guardian) "
		+ "and gate the branch on the verdict.")
public class GuardNode extends AbstractMediaNode<GuardNodeOptions> {

	private static final Logger log = LoggerFactory.getLogger(GuardNode.class);

	public static final String KIND = "guard";

	private static final String SCHEMA_TYPE = "guard";

	private static final String METRICS_LABEL = "guard";

	@PortDoc(label = "Text", required = false,
		description = "The prose to classify - a transcript, document body, OCR result or caption. Leave unwired to classify the item's pixels instead")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	/**
	 * The item's own pixels. Like every other image-consuming node in the tree this declares what
	 * the node accepts and is read through {@code ctx.media()} — a {@code media/*} port carries the
	 * item, not an edge, so there is nothing to fetch from {@code ctx.input}.
	 */
	@PortDoc(label = "Image", required = false,
		description = "The item's pixels, classified when the asset is an image. Needs a multimodal guard model "
			+ "(Llama Guard 4 or ShieldGemma 2) served by vLLM")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	/**
	 * The gate. Typed {@code control/filter} so the engine treats it as a branch decision — a node
	 * wired behind this port does not run for an item the guard flagged.
	 */
	@PortDoc(label = "Safe", description = "True when the item is below the threshold. Wire a node behind this port to run it only on safe items")
	public static final OutputPort<Boolean> OUT_SAFE = OutputPort.one("safe", ContentTypeRegistry.CONTROL_FILTER, Boolean.class);

	@PortDoc(label = "Label", description = "'safe' or 'unsafe'")
	public static final OutputPort<String> OUT_LABEL = OutputPort.one("label", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Score", description = "P(unsafe) in [0,1], comparable across the three model families")
	public static final OutputPort<Double> OUT_SCORE = OutputPort.one("score", ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	/** MANY so it fans straight into the tag node's {@code labels} input. */
	@PortDoc(label = "Categories", description = "The canonical harm categories that were flagged, one element each")
	public static final OutputPort<String> OUT_CATEGORIES = OutputPort.many("categories", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Result", description = "The stored verdict: score, threshold, per-category scores and the model's raw answer")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_JSON, String.class);

	private static final int RESULT_CACHE_SIZE = 10_000;

	/** In-heap skip cache of the stored payload. Non-durable — the durable copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final GuardClient guardClient;

	@Inject
	public GuardNode(@Nullable LoomClient client, CortexOptions cortexOptions, GuardNodeOptions options, GuardClient guardClient) {
		super(client, cortexOptions, options);
		this.guardClient = guardClient;
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		return resolveText(ctx) != null || ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		String text = resolveText(ctx);
		boolean isImage = ctx.media().isImage();

		if (text == null && !isImage) {
			return ctx.skipped("no upstream text and the item is not an image").next();
		}
		if (text == null && !options().getFamily().supportsImages()) {
			// Fail rather than skip. A guard that quietly passes every image because the configured
			// model cannot read one is exactly the silent hole a downstream gate must not have.
			String reason = "The item is an image but " + options().getFamily()
				+ " is a text-only model. Wire text into this node, or use LLAMA_GUARD_4 / SHIELDGEMMA_2 served by vLLM.";
			recordNodeResult(asset, ctx, ResultState.FAILED, reason, producerVersion(), null);
			return ctx.failure(reason).abort();
		}
		// An image is only examined when the configured family can actually look at one. Wiring text
		// into a text-only guard on an image asset is a legitimate configuration - it classifies the
		// caption or the OCR result - so it must not fail.
		LoomMedia image = isImage && options().getFamily().supportsImages() ? ctx.media() : null;

		String cacheKey = cacheKey(ctx, text, image);
		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			// The durable copy already exists in Loom, so re-persisting is skipped along with the calls.
			metrics.recordAiCacheHit(METRICS_LABEL);
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		try {
			GuardDialect dialect = GuardDialect.of(options().getFamily());
			List<String> codes = options().effectiveCategories();

			List<GuardProbeResult> results = new ArrayList<>();
			if (text != null) {
				results.addAll(run(dialect.textProbes(text, codes, options()), dialect, null));
			}
			if (image != null) {
				results.addAll(run(dialect.imageProbes(codes, options()), dialect, loadImage(image)));
			}

			// The subject is only "text" or "image" when exactly one was wired; wiring both means the
			// score is the worse of the two and saying otherwise would misattribute it.
			String subject = text != null && image != null ? GuardVerdict.SUBJECT_TEXT + "+" + GuardVerdict.SUBJECT_IMAGE
				: text != null ? GuardVerdict.SUBJECT_TEXT : GuardVerdict.SUBJECT_IMAGE;
			GuardVerdict verdict = GuardVerdict.of(results, options(), subject, text == null ? null : text.length());

			JsonObject payload = verdict.toJson();
			emit(ctx, payload);
			ctx.preview(OUT_RESULT, verdict.toMarkdown());
			resultCache.put(cacheKey, payload.encode());

			ctx.print("DONE", verdict.label() + " (" + verdict.score() + ", " + results.size() + " probe(s))");
			persist(ctx, asset, payload);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to guard media {}", ctx.media().absolutePath(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			// abort(), not next(): NodeContextImpl.next() looks only at the skip reason, so a failure
			// returned with next() is reported as SUCCESS.
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Issue every probe and parse each answer.
	 *
	 * <p>
	 * Sequential on purpose. For the per-policy families this is one call per selected category, and
	 * firing them at a single small GPU in parallel makes the whole batch slower; concurrency across
	 * <em>items</em> is the pipeline's job and is what {@code defaultConcurrency} controls.
	 * </p>
	 */
	private List<GuardProbeResult> run(List<GuardProbe> probes, GuardDialect dialect, BufferedImage image) throws Exception {
		List<GuardProbeResult> results = new ArrayList<>();
		for (GuardProbe probe : probes) {
			long start = System.currentTimeMillis();
			GuardCompletion completion;
			try {
				completion = image == null
					? guardClient.complete(probe, options().getModel())
					: guardClient.complete(probe, options().getModel(), image);
			} catch (Exception e) {
				metrics.recordAiCall(METRICS_LABEL, false, System.currentTimeMillis() - start);
				throw e;
			}
			metrics.recordAiCall(METRICS_LABEL, true, System.currentTimeMillis() - start);
			results.add(dialect.parse(probe, completion, options()));
		}
		return results;
	}

	/** Decode and downscale the item's pixels. */
	private BufferedImage loadImage(LoomMedia media) throws Exception {
		BufferedImage image = GuardImages.read(new File(media.absolutePath()));
		return GuardImages.downscale(image, options().getMaxImageDim());
	}

	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		boolean safe = payload.getBoolean("safe", Boolean.TRUE);
		ctx.output(OUT_SAFE, safe);
		ctx.output(OUT_LABEL, safe ? "safe" : "unsafe");
		ctx.output(OUT_SCORE, payload.getDouble("score"));
		ctx.output(OUT_RESULT, payload.encode());
		payload.getJsonArray("categories", new io.vertx.core.json.JsonArray()).stream()
			.filter(JsonObject.class::isInstance)
			.map(JsonObject.class::cast)
			.map(hit -> hit.getString("canonical"))
			.distinct()
			.forEach(canonical -> ctx.outputElement(OUT_CATEGORIES, canonical));
	}

	/**
	 * Persist the verdict as a {@code guard} JSON component and record the ledger entry. Best-effort
	 * and a no-op when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// One verdict per asset per node kind. Unlike translate there is nothing to key several
			// rows by: a second guard node with a different model is a different opinion about the
			// same question, and the natural reading of "is this asset safe" is one answer.
			request.setVariant("");
			request.setProducerVersion(producerVersion());
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist the guard verdict for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
		}
	}

	/**
	 * What produced the verdict. The family is part of it, not just the model id: the same checkpoint
	 * read through two dialects gives two different answers, so a stored row has to say which one it
	 * was.
	 *
	 * @return the producer version
	 */
	String producerVersion() {
		return KIND + "/1:" + options().getFamily() + ":" + options().getModel();
	}

	/** The wired text, truncated to {@code maxChars}, or null when the port carries nothing usable. */
	private String resolveText(NodeContext<LoomMedia> ctx) {
		String text = ctx.optionalInput(IN_TEXT).orElse(null);
		if (text == null || text.isBlank()) {
			return null;
		}
		if (text.length() > options().getMaxChars()) {
			log.warn("Truncating {} characters of input to the configured maxChars of {}", text.length(), options().getMaxChars());
			return text.substring(0, options().getMaxChars());
		}
		return text;
	}

	/**
	 * Cache key covering everything that changes the verdict.
	 *
	 * <p>
	 * Deliberately not the media path alone. The text arrives from an <em>edge</em>, so the same
	 * asset can be guarded against a transcript in one graph and an OCR result in another, and a
	 * path-keyed cache would serve the first verdict for the second question. Every option that
	 * reaches the model is in here too, so retuning the threshold or narrowing the categories
	 * re-runs rather than replaying a stale answer.
	 * </p>
	 */
	private String cacheKey(NodeContext<LoomMedia> ctx, String text, LoomMedia image) {
		int inputsHash = Objects.hash(text,
			image == null ? null : image.absolutePath(),
			options().getFamily(), options().getModel(), options().effectiveCategories(),
			options().getThreshold(), options().getPromptTemplate(), options().getMaxImageDim());
		return ctx.media().absolutePath() + "|" + Integer.toHexString(inputsHash);
	}
}
