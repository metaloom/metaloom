package io.metaloom.cortex.node.translate;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.llm.AbstractLlmNodeOptions;

/**
 * Worker-level configuration for the translate node.
 *
 * <p>
 * One node instance translates into <em>one</em> language. That is why the target language is an
 * option rather than a list: the stored component is keyed by {@code variant = targetLanguage}, so
 * two instances in the same graph write two rows on the same asset and both survive. A single node
 * fanning out to several languages would have to invent a port per language, which is the dynamic
 * port machinery the llm and vlm nodes carry — worth it there, where the prompts are the whole point
 * of the node, and not worth it here.
 * </p>
 */
public class TranslateNodeOptions extends AbstractLlmNodeOptions<TranslateNodeOptions> {

	public static final String KEY = "translate";

	public static final String DEFAULT_TARGET_LANGUAGE = "en";

	public static final String DEFAULT_SOURCE_LANGUAGE = "auto";

	public static final String DEFAULT_MODEL = "google/gemma-2-27b-it";

	/** The placeholder the template must carry, or the source text would never reach the model. */
	public static final String TEXT_PLACEHOLDER = "${text}";

	public static final String DEFAULT_PROMPT_TEMPLATE = """
		You are a translator. Translate the text below into ${targetLanguage}.
		The source language is ${sourceLanguage}.

		Return only the translation. Do not explain, do not add notes, and do not repeat the source.
		Preserve the line and paragraph structure of the source.

		Text:
		${text}
		""";

	private String targetLanguage = DEFAULT_TARGET_LANGUAGE;

	/** {@code auto} lets the model work it out; naming it helps when the source is short or mixed. */
	private String sourceLanguage = DEFAULT_SOURCE_LANGUAGE;

	private String model = DEFAULT_MODEL;

	private String promptTemplate = DEFAULT_PROMPT_TEMPLATE;

	/** Upper bound on one request. Larger documents are split on paragraph and sentence boundaries. */
	private int maxChunkChars = 8000;

	/** Upper bound on the whole input, so one pathological asset cannot spend the worker's evening. */
	private int maxChars = 200000;

	public String getTargetLanguage() {
		return targetLanguage;
	}

	public TranslateNodeOptions setTargetLanguage(String targetLanguage) {
		this.targetLanguage = targetLanguage;
		return this;
	}

	public String getSourceLanguage() {
		return sourceLanguage;
	}

	public TranslateNodeOptions setSourceLanguage(String sourceLanguage) {
		this.sourceLanguage = sourceLanguage;
		return this;
	}

	public String getModel() {
		return model;
	}

	public TranslateNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public String getPromptTemplate() {
		return promptTemplate;
	}

	public TranslateNodeOptions setPromptTemplate(String promptTemplate) {
		this.promptTemplate = promptTemplate;
		return this;
	}

	public int getMaxChunkChars() {
		return maxChunkChars;
	}

	public TranslateNodeOptions setMaxChunkChars(int maxChunkChars) {
		this.maxChunkChars = maxChunkChars;
		return this;
	}

	public int getMaxChars() {
		return maxChars;
	}

	public TranslateNodeOptions setMaxChars(int maxChars) {
		this.maxChars = maxChars;
		return this;
	}

	@Override
	protected TranslateNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		errors.addAll(validateEndpoint());

		if (targetLanguage == null || targetLanguage.isBlank()) {
			errors.add("targetLanguage must not be empty");
		}
		if (model == null || model.isBlank()) {
			errors.add("model must not be empty");
		}
		if (promptTemplate == null || promptTemplate.isBlank()) {
			errors.add("promptTemplate must not be empty");
		} else if (!promptTemplate.contains(TEXT_PLACEHOLDER)) {
			// Without it the model is asked to translate nothing and answers something plausible
			// about an empty document - a failure that looks like a success all the way to storage.
			errors.add("promptTemplate must contain " + TEXT_PLACEHOLDER);
		}
		if (maxChunkChars < 200) {
			errors.add("maxChunkChars must be at least 200, got " + maxChunkChars);
		}
		if (maxChars < 1) {
			errors.add("maxChars must be at least 1, got " + maxChars);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
