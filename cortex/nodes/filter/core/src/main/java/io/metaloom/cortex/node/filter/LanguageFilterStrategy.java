package io.metaloom.cortex.node.filter;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.vertx.core.json.JsonObject;

/**
 * Decides an item's language with a language model and routes it to the matching bucket.
 *
 * <p>
 * A model rather than a detector library is a starting point chosen for reach, not for cost: it
 * needs no new dependency, no model download and no sidecar, and it generalises to the other
 * {@code filterBy} values that will follow. It does cost one round trip per item. If language
 * routing ever becomes the bottleneck, an embedded detector is the obvious replacement and only
 * this class changes — the ports, the routing and the editor do not.
 * </p>
 */
public class LanguageFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(LanguageFilterStrategy.class);

	/**
	 * Bumped whenever a change here would give a different answer for the same input. It is mixed
	 * into the node's cache key and its {@code producerVersion}, so an old verdict is neither
	 * re-emitted from the worker's heap nor mistaken for a current one in Loom.
	 */
	static final String PROMPT_VERSION = "lang/1";

	private static final String PROMPT = """
		You identify the language a text is written in.

		Answer with JSON only, in exactly this shape:
		{"bucket": "<one of the ids below>", "confidence": <0.0 to 1.0>}

		Allowed ids:
		${buckets}

		Use "other" when the text is in none of those languages, or when there is too little text
		to tell. Do not invent an id that is not listed.

		Text:
		${text}
		""";

	private final LLMProvider provider;

	@Inject
	public LanguageFilterStrategy(LLMProvider provider) {
		this.provider = provider;
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.LANGUAGE;
	}

	@Override
	public Classification classify(NodeContext<LoomMedia> ctx, FilterNodeOptions options, List<FilterBucket> buckets, String text) {
		if (text == null || text.isBlank()) {
			// Not a failure: an item with no text wired in genuinely belongs in 'other'. Skipping
			// instead would emit nothing at all, which under port routing closes the 'other' branch
			// too - exactly backwards.
			return Classification.other("no text was wired into this node");
		}

		String menu = buckets.stream().map(FilterBucket::describeForPrompt).collect(Collectors.joining("\n"))
			+ (buckets.isEmpty() ? "" : "\n") + Classification.OTHER + " (anything else)";
		String excerpt = text.length() > options.getMaxTextChars() ? text.substring(0, options.getMaxTextChars()) : text;

		LargeLanguageModel model = new LargeLanguageModelImpl(options.getModel(), options.openaiUrl(), 2048);
		Prompt prompt = new PromptImpl(PROMPT);
		prompt.set("buckets", menu);
		prompt.set("text", excerpt);
		LLMContext llmCtx = LLMContext.ctx(prompt, model);

		// A provider failure propagates: an unreachable model means the worker could not do the job
		// it was given, which is a failure and not a routing decision.
		JsonObject answer = provider.generateJson(llmCtx);

		JsonObject detail = new JsonObject()
			.put("model", options.getModel())
			.put("promptVersion", PROMPT_VERSION)
			.put("answer", answer);

		String raw = answer == null ? null : answer.getString("bucket");
		if (raw == null || raw.isBlank()) {
			log.warn("Language filter got no bucket back for {}; routing to '{}'", ctx.media().absolutePath(), Classification.OTHER);
			return Classification.of(Classification.OTHER, 0, detail);
		}

		double confidence = answer.getDouble("confidence", 1.0);
		for (FilterBucket bucket : buckets) {
			if (bucket.matches(raw)) {
				if (confidence < options.getMinConfidence()) {
					return Classification.of(Classification.OTHER, confidence,
						detail.put("reason", "confidence " + confidence + " below the configured minimum"));
				}
				return Classification.of(bucket.id(), confidence, detail);
			}
		}

		// An answer that names no configured bucket - including a literal "other" - is the catch-all.
		// Logged at debug rather than warn: with a bucket set of {de, en} most items legitimately
		// land here, and a warn per item would drown the log.
		log.debug("Language filter answer '{}' matched no bucket for {}", raw, ctx.media().absolutePath());
		return Classification.of(Classification.OTHER, confidence, detail);
	}
}
