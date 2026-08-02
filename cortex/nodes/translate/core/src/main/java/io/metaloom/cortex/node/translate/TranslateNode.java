package io.metaloom.cortex.node.translate;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.llm.LlmEndpoint;
import io.metaloom.cortex.llm.LlmInvoker;
import io.metaloom.cortex.llm.TextChunker;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

/**
 * Translates text produced by an upstream node into a configured target language.
 *
 * <p>
 * Like {@code sentiment} and {@code tts} this node never looks at the media bytes: it translates
 * whatever arrives on its {@link #IN_TEXT} port — a Whisper transcript, Tika document text, an OCR
 * result, a VLM answer — and attaches the translation to the asset. The <em>edge</em> decides where
 * the text comes from, which is why the node has no option naming an upstream node.
 * </p>
 *
 * <p>
 * This is deliberately not a mode of the {@code llm} node. That node's only input is {@code media}
 * and it builds its prompt from the filename, so no upstream transcript can reach it — a limitation
 * the translation playbook used to have to warn readers about. What the two share is the LLM
 * plumbing, and that lives in {@code cortex/llm-common}.
 * </p>
 *
 * <p>
 * The result is stored as an {@code asset_json_comp} row with {@code schemaType="translation"} and
 * {@code variant} set to the target language, so one asset can carry {@code en}, {@code de} and
 * {@code fr} side by side — one row per translate node in the graph.
 * </p>
 */
public class TranslateNode extends AbstractMediaNode<TranslateNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TranslateNode.class);

	private static final String METRICS_LABEL = "translate";

	private static final String SCHEMA_TYPE = "translation";

	/**
	 * The prose to translate.
	 *
	 * <p>
	 * Typed {@code text/*} so any extractor can feed it: {@code text/transcript} from whisper and
	 * {@code text/plain} from tika, ocr or vlm are all assignable to it.
	 * </p>
	 */
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	/** The translation itself, ready to be wired into {@code tts} or an s3 sink. */
	public static final OutputPort<String> OUT_TRANSLATION = OutputPort.one("translation", ContentTypeRegistry.TEXT_PLAIN, String.class);

	/** The target language tag, so a downstream node can pick a voice without repeating the option. */
	public static final OutputPort<String> OUT_LANGUAGE = OutputPort.one("language", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** The full stored payload, including how much text was seen and how many calls it took. */
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_JSON, String.class);

	private static final int RESULT_CACHE_SIZE = 10_000;

	/** In-heap skip cache of the stored payload. Non-durable - the durable copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final LLMProvider provider;

	@Inject
	public TranslateNode(@Nullable LoomClient client, CortexOptions cortexOptions, TranslateNodeOptions options, LLMProvider provider) {
		super(client, cortexOptions, options);
		this.provider = provider;
	}

	@Override
	public String name() {
		return "translate";
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
		String text = resolveText(ctx);
		if (text == null) {
			return ctx.skipped("no upstream text").next();
		}

		String cacheKey = cacheKey(ctx, text);
		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			// The durable copy already exists in Loom, so re-persisting is skipped along with the calls.
			metrics.recordAiCacheHit(METRICS_LABEL);
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		String model = options().getModel();
		try {
			LlmInvoker invoker = new LlmInvoker(provider, LlmEndpoint.of(options()));
			List<String> chunks = TextChunker.split(text, options().getMaxChunkChars());

			StringBuilder translated = new StringBuilder();
			for (String chunk : chunks) {
				String answer = invoker.generate(model, prompt(chunk), metrics, METRICS_LABEL);
				if (answer == null || answer.isBlank()) {
					continue;
				}
				if (!translated.isEmpty()) {
					translated.append(TextChunker.JOIN_SEPARATOR);
				}
				translated.append(answer.strip());
			}

			JsonObject payload = buildPayload(text, translated.toString(), chunks.size(), model);
			emit(ctx, payload);
			resultCache.put(cacheKey, payload.encode());

			ctx.print("DONE", options().getTargetLanguage() + " (" + chunks.size() + " chunk(s))");
			persist(ctx, asset, payload, model);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to translate media {} into {}", ctx.media().absolutePath(), options().getTargetLanguage(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
			// abort(), not next(): NodeContextImpl.next() looks only at the skip reason, so a failure
			// returned with next() is reported as SUCCESS.
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Build the prompt for one chunk. The template's own placeholders are filled here rather than in
	 * the invoker so an operator can rewrite the whole instruction in worker YAML.
	 */
	private Prompt prompt(String chunk) {
		Prompt prompt = new PromptImpl(options().getPromptTemplate());
		prompt.set("targetLanguage", options().getTargetLanguage());
		prompt.set("sourceLanguage", options().getSourceLanguage());
		prompt.set("text", chunk);
		return prompt;
	}

	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		ctx.output(OUT_TRANSLATION, payload.getString("text"));
		ctx.output(OUT_LANGUAGE, payload.getString("targetLanguage"));
		ctx.output(OUT_RESULT, payload.encode());
	}

	private JsonObject buildPayload(String source, String translation, int chunkCount, String model) {
		return new JsonObject()
			.put("text", translation)
			.put("targetLanguage", options().getTargetLanguage())
			.put("sourceLanguage", options().getSourceLanguage())
			.put("model", model)
			.put("chunkCount", chunkCount)
			.put("sourceChars", source.length())
			.put("translatedChars", translation.length());
	}

	/**
	 * Persist the translation as a {@code translation} JSON component and record the ledger entry.
	 * Best-effort and a no-op when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload, String model) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// The target language is the variant, so several translate nodes coexist on one asset.
			request.setVariant(options().getTargetLanguage());
			request.setProducerVersion(model);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, model, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist translation for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
		}
	}

	/**
	 * The wired text, truncated to {@code maxChars}, or null when the port carries nothing usable.
	 */
	private String resolveText(NodeContext<LoomMedia> ctx) {
		String text = ctx.input(IN_TEXT);
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
	 * Cache key covering everything that changes the answer.
	 *
	 * <p>
	 * Deliberately not the media path alone. The same asset can be fed different upstream text, and
	 * the same text asked for a different language by a second translate node — both would be served
	 * the first answer from a path-keyed cache, and a stale translation never surfaces as an error.
	 * </p>
	 */
	private String cacheKey(NodeContext<LoomMedia> ctx, String text) {
		int inputsHash = Objects.hash(text,
			options().getTargetLanguage(), options().getSourceLanguage(),
			options().getModel(), options().getPromptTemplate(), options().getMaxChunkChars());
		return ctx.media().absolutePath() + "|" + Integer.toHexString(inputsHash);
	}
}
