package io.metaloom.cortex.node.translate;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
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

	// The orders interleave these with openaiUrl (100) and contextWindow (110) inherited from
	// AbstractLlmNodeOptions, which is the order TranslateDescriptorProvider - the golden contract -
	// declares. Field-declaration order alone would put both inherited knobs ahead of every field here.
	@ParamDoc(label = "Target Language", description = "Language to translate into; also the variant the result is stored under", order = 40)
	private String targetLanguage = DEFAULT_TARGET_LANGUAGE;

	/** {@code auto} lets the model work it out; naming it helps when the source is short or mixed. */
	@ParamDoc(label = "Source Language", description = "Language of the input, or 'auto' to let the model work it out", order = 50)
	private String sourceLanguage = DEFAULT_SOURCE_LANGUAGE;

	@ParamDoc(label = "Model", description = "Model id asked to translate; also recorded as the producer version", order = 60)
	private String model = DEFAULT_MODEL;

	// omitDefault reproduces the hand-written descriptor exactly: it advertised no default for this
	// field even though the field has one. Preserving that is the sweep's job; deciding whether the
	// editor should pre-fill DEFAULT_PROMPT_TEMPLATE is a separate, reviewable change.
	@ParamDoc(label = "Prompt Template", description = "Instruction sent with each chunk; must contain the ${text} placeholder",
		order = 120, omitDefault = true)
	private String promptTemplate = DEFAULT_PROMPT_TEMPLATE;

	/** Upper bound on one request. Larger documents are split on paragraph and sentence boundaries. */
	@ParamDoc(label = "Max Chunk Characters",
		description = "Longer input is split on paragraph and sentence boundaries into chunks of this size", min = "200", order = 130)
	private int maxChunkChars = 8000;

	/** Upper bound on the whole input, so one pathological asset cannot spend the worker's evening. */
	@ParamDoc(label = "Max Characters", description = "Upper bound on the total input text", min = "1", order = 140)
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
