package io.metaloom.loom.test.integration.node.docs;

import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import io.metaloom.ai.genai.llm.openai.OpenAILLMProvider;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.llm.AbstractLlmNodeOptions;
import io.metaloom.cortex.node.filter.FilterBy;
import io.metaloom.cortex.node.filter.FilterNode;
import io.metaloom.cortex.node.filter.FilterNodeOptions;
import io.metaloom.cortex.node.filter.FilterStrategy;
import io.metaloom.cortex.node.filter.LanguageFilterStrategy;
import io.metaloom.cortex.node.llm.LLMNode;
import io.metaloom.cortex.node.llm.LLMNodeOptions;
import io.metaloom.cortex.node.llm.LLMNodePrompt;
import io.metaloom.cortex.node.translate.TranslateNode;
import io.metaloom.cortex.node.translate.TranslateNodeOptions;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The nodes that talk to an OpenAI-compatible language model.
 *
 * <p>
 * All four run against a <strong>real</strong> model server — `loom-test-env/llamacpp/start.sh`
 * puts one on the port these nodes already default to. That matters more here than anywhere else in
 * the catalogue: the node integration tests for this family inject a mock provider, and a screenshot
 * of a mocked classifier is a picture of a routing decision nothing made. Whatever these pages show
 * is what a real model said about real input.
 * </p>
 *
 * <p>
 * The one deviation from stock configuration is the model name, which has to be the one the local
 * server actually serves rather than the shipped default. It is recorded in the fixture's
 * {@code nodeData}, so the picture shows the setting it was taken at.
 * </p>
 */
public final class LlmRecipes {

	/** Where the nodes already look by default — see {@code AbstractLlmNodeOptions}. */
	private static final String HEALTH = "http://127.0.0.1:8080/v1/models";

	/** Whatever `loom-test-env/llamacpp/start.sh` is serving. */
	private static final String MODEL = System.getProperty("loom.docsLlmModel", "ggml-org/Qwen3-4B-GGUF:Q4_K_M");

	private static final String HINT =
		"start an OpenAI-compatible server on 8080 — `cd loom-test-env/llamacpp && PORT=8080 ./start.sh`";

	private LlmRecipes() {
	}

	private static Requirement llmRunning() {
		return Requirement.service("language model", HEALTH, HINT);
	}

	/**
	 * A short transcript, so every page in this family is reasoning about the same text.
	 *
	 * <p>
	 * Shared with the sentiment recipe rather than duplicated: a reader following the catalogue then
	 * sees one German complaint translated, routed and scored, and can compare what each node made
	 * of it.
	 * </p>
	 */
	static final String TRANSCRIPT =
		"Guten Tag. Wir haben die Lieferung heute erhalten, aber zwei Kartons waren beschädigt. "
			+ "Bitte melden Sie sich, damit wir Ersatz organisieren können.";

	public static DocsFixtureRecipe llm() {
		// No text upstream: this node declares a single `media` input and binds only the asset's
		// name into the prompt. It reads as though a transcript could be piped in, and it cannot —
		// so the picture shows what the node does rather than what the page used to imply.
		return recipe("llm", List.of(new Upstream("filesystem-source", "media", "media")), env -> {
			LLMNodePrompt prompt = new LLMNodePrompt();
			prompt.setModel(MODEL);
			// The node calls `generateJson`, so a prompt that asks for prose gets a decode failure
			// rather than an answer. Every prompt on this node has to name the shape it wants back.
			prompt.setPrompt("""
				Given only the file name ${name}, guess what the file contains.
				Answer with a JSON object and nothing else, with the keys:
				  "subject"    - a short phrase naming the likely subject
				  "media_type" - one of "video", "image", "audio", "document"
				  "confidence" - a number between 0 and 1
				""");
			LLMNodeOptions options = new LLMNodeOptions();
			options.setPrompts(Map.of("summary", prompt));
			LLMNode node = new LLMNode(null, env.cortexOptions("llm"), options, new OpenAILLMProvider());
			node.initialize();
			var media = env.video1();
			NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media));
			return new Outcome(node.process(ctx), env.displayPath(media),
				new JsonObject().put("prompts", new JsonObject().put("summary",
					new JsonObject().put("model", MODEL).put("prompt", prompt.getPrompt()))));
		});
	}

	public static DocsFixtureRecipe translate() {
		return recipe("translate", List.of(new Upstream("whisper", "transcript", "text")), env -> {
			TranslateNodeOptions options = new TranslateNodeOptions()
				.setSourceLanguage("de")
				.setTargetLanguage("en")
				.setModel(MODEL);
			TranslateNode node = new TranslateNode(null, env.cortexOptions("translate"), options,
				new OpenAILLMProvider());
			node.initialize();
			var media = env.video1();
			NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media),
				NodeInputs.builder().input(TranslateNode.IN_TEXT, TRANSCRIPT).build());
			return new Outcome(node.process(ctx), env.displayPath(media),
				new JsonObject().put("sourceLanguage", "de").put("targetLanguage", "en").put("model", MODEL));
		});
	}

	public static DocsFixtureRecipe filter() {
		return recipe("filter", List.of(new Upstream("tika", "content", "text")), env -> {
			FilterNodeOptions options = new FilterNodeOptions();
			options.setModel(MODEL);
			// The real strategy, not the stub the routing unit tests inject: routing is the node's
			// whole subject, and a canned classification would be a picture of nothing deciding.
			Provider<FilterStrategy> strategy = () -> new LanguageFilterStrategy(new OpenAILLMProvider());
			FilterNode node = new FilterNode(null, env.cortexOptions("filter"), options,
				Map.of(FilterBy.LANGUAGE, strategy));
			JsonObject nodeDef = new JsonObject()
				.put("id", "filter")
				.put("filterBy", "LANGUAGE")
				.put("model", MODEL)
				.put("buckets", new JsonArray()
					.add(new JsonObject().put("id", "de").put("label", "German").put("match", "German"))
					.add(new JsonObject().put("id", "en").put("label", "English").put("match", "English")));
			node.configure(nodeDef);
			node.initialize();
			var media = env.video1();
			NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media),
				NodeInputs.builder().input(FilterNode.IN_TEXT, TRANSCRIPT).build());
			return new Outcome(node.process(ctx), env.displayPath(media), nodeDef);
		});
	}

	private static DocsFixtureRecipe recipe(String kind, List<Upstream> upstream, Body body) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return kind;
			}

			@Override
			public Requirement requirement() {
				return llmRunning();
			}

			@Override
			public List<Upstream> upstream() {
				return upstream;
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				return body.run(env);
			}
		};
	}

	@FunctionalInterface
	interface Body {
		Outcome run(FixtureEnv env) throws Exception;
	}

	/** Kept next to the recipes so the shared default is visible where it is overridden. */
	static String defaultOpenaiUrl() {
		return AbstractLlmNodeOptions.DEFAULT_OPENAI_URL;
	}
}
