package io.metaloom.cortex.node.llm;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.llm.LlmEndpoint;
import io.metaloom.cortex.llm.LlmInvoker;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

/**
 * Asks a language model one question per configured prompt about each media item.
 *
 * <p>
 * The output side is <strong>dynamic</strong>: {@code LlmPortResolver} derives one
 * {@code result_<promptId>} port from the {@code prompts} option, so there is no static output port
 * to declare here.
 * </p>
 */
@NodeSpec(nodeId = "llm", name = "LLM (Large Language Model)", icon = "psychology", category = NodeCategory.ANALYSIS,
	description = "Process media through an LLM served over an OpenAI-compatible API, with configurable prompts.",
	dynamicPorts = true,
	// contextWindow is documented on AbstractLlmNodeOptions for the nodes that advertise it; this
	// node's contract never has, so it is hidden here rather than un-documented on the shared base.
	parameters = @ParamOverride(key = "contextWindow", hidden = true))
public class LLMNode extends AbstractMediaNode<LLMNodeOptions> {

	@PortDoc(label = "Media", description = "The media item the configured prompts are asked about")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/** Metrics provider label. */
	private static final String METRICS_LABEL = "llm";

	/** In-heap skip cache of the per-prompt LLM outputs, keyed by media path, to avoid re-running the model within this worker's lifetime. Non-durable -
	 * the durable copy lives in Loom. */
	private final LocalResultCache<Map<String, String>> resultCache = new LocalResultCache<>(50_000);

	/** The LLM provider (injected). Speaks the OpenAI chat-completions protocol; tests point it at a mock server. */
	private final LLMProvider provider;

	@Inject
	public LLMNode(@Nullable LoomClient client, CortexOptions cortexOption, LLMNodeOptions options, LLMProvider provider) {
		super(client, cortexOption, options);
		this.provider = provider;
		if (options.getPrompts().isEmpty()) {
			options.setPrompts(defaultPrompts());
		}
	}

	private Map<String, LLMNodePrompt> defaultPrompts() {
		LLMNodePrompt prompt = new LLMNodePrompt();
		prompt.setModel("google/gemma-2-27b-it");
		prompt.setPrompt("""
			Extract metadata from the given filename and output JSON.

			Example JSON Format:
			{
				"format": "1080p",
				"genre": "action",
				"year": "2024",
				"title": "The human readable title"
			}
			Filename:
			${name}
			""");
		return Map.of("default", prompt);
	}

	@Override
	public String name() {
		return "llm";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		// Every media has a filename thus can be processed.
		return true;
	}

	/**
	 * The output port carrying a given prompt's answer.
	 *
	 * <p>
	 * The id must stay {@code result_<promptId>}: {@code LlmPortResolver} derives the descriptor's
	 * ports from the same {@code prompts} option and produces exactly these names. The descriptor
	 * used to declare a single {@code llm_result} while the node wrote {@code llm_result_<promptId>},
	 * so nothing downstream could ever bind to what it actually emitted.
	 * </p>
	 */
	public static OutputPort<String> resultPort(String promptId) {
		return OutputPort.one("result_" + promptId, ContentTypeRegistry.TEXT_PLAIN, String.class);
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {

		String path = ctx.media().absolutePath();
		// Re-emit the locally cached prompt results instead of re-running the LLM. On a hit the durable copy already exists in Loom, so we also skip
		// re-persisting.
		Map<String, String> cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit(METRICS_LABEL);
			cached.forEach((promptId, answer) -> ctx.output(resultPort(promptId), answer));
			return ctx.origin(LOCAL).next();
		}
		Map<String, String> answers = new HashMap<>();
		LlmInvoker invoker = new LlmInvoker(provider, LlmEndpoint.of(options()));

		for (Entry<String, LLMNodePrompt> entry : options().getPrompts().entrySet()) {
			String promptId = entry.getKey();
			String modelName = entry.getValue().getModel();
			String promptStr = entry.getValue().getPrompt();

			Prompt prompt = new PromptImpl(promptStr);
			prompt.set("name", ctx.media().file().getName());

			JsonObject json = invoker.generateJson(modelName, prompt, metrics, METRICS_LABEL);

			ctx.output(resultPort(promptId), json.encode());
			answers.put(promptId, json.encode());
			persist(ctx, asset, promptId, modelName, json);
		}

		resultCache.put(path, answers);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Persist one prompt's LLM result as an {@code llm} JSON component (variant = prompt id) and record a ledger entry. Best-effort and a no-op when the
	 * asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String promptId, String modelName, JsonObject json) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("llm");
			request.setVariant(promptId);
			request.setProducerVersion(modelName);
			request.setData(json);
			java.util.UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, modelName, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist llm result for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), modelName, null);
		}
	}

}
