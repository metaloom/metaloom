package io.metaloom.cortex.node.llm;

import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

public class LLMNode extends AbstractMediaNode<LLMNodeOptions> {

	@Inject
	public LLMNode(@Nullable LoomClient client, CortexOptions cortexOption, LLMNodeOptions options) {
		super(client, cortexOption, options);
		if (options.getPrompts().isEmpty()) {
			options.setPrompts(defaultPrompts());
		}
	}

	private Map<String, LLMNodePrompt> defaultPrompts() {
		LLMNodePrompt prompt = new LLMNodePrompt();
		prompt.setModel("gemma2:27b");
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
	 * Create a typed output key for a given prompt id.
	 */
	public static NodeOutputKey<String> resultKey(String promptId) {
		return NodeOutputKey.of("llm_result_" + promptId, String.class);
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {

		for (Entry<String, LLMNodePrompt> entry : options().getPrompts().entrySet()) {
			String promptId = entry.getKey();
			String modelName = entry.getValue().getModel();
			String promptStr = entry.getValue().getPrompt();

			LargeLanguageModel model = new LargeLanguageModelImpl(modelName, options().ollamaUrl(), 2048, LLMProviderType.OLLAMA);
			Prompt prompt = new PromptImpl(promptStr);
			prompt.set("name", ctx.media().file().getName());
			LLMContext llmCtx = LLMContext.ctx(prompt, model);

			LLMProvider provider = new OllamaLLMProvider();
			JsonObject json = provider.generateJson(llmCtx);

			ctx.output(resultKey(promptId), json.encode());
			persist(ctx, asset, promptId, modelName, json);
		}

		return NodeResult.success(ctx.outputs());
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
