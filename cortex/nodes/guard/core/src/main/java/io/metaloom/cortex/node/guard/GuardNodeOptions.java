package io.metaloom.cortex.node.guard;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.llm.AbstractLlmNodeOptions;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Worker-level configuration for the guard node.
 *
 * <p>
 * {@code family} is the load-bearing knob: it selects the prompt dialect, the native taxonomy and
 * how many backend calls one verdict costs. {@code model} only names the checkpoint the endpoint
 * should serve — pointing a {@code SHIELDGEMMA} family at a Llama Guard checkpoint produces
 * confident nonsense, not an error, which is why the family is an explicit choice rather than
 * something guessed from the model id.
 * </p>
 */
public class GuardNodeOptions extends AbstractLlmNodeOptions<GuardNodeOptions> {

	public static final String KEY = "guard";

	public static final GuardFamily DEFAULT_FAMILY = GuardFamily.LLAMA_GUARD_3;

	/** A GGUF of this exists for llama.cpp, which is the backend the shipped sidecar runs. */
	public static final String DEFAULT_MODEL = "meta-llama/Llama-Guard-3-8B";

	public static final double DEFAULT_THRESHOLD = 0.5d;

	public static final int DEFAULT_MAX_CHARS = 8000;

	public static final int DEFAULT_MAX_IMAGE_DIM = 1024;

	@ParamDoc(label = "Model Family",
		description = "Which guard model is being served. Selects the prompt format, the harm taxonomy and how many calls one verdict takes",
		order = 40)
	private GuardFamily family = DEFAULT_FAMILY;

	@ParamDoc(label = "Model", description = "Model id to select on the endpoint; also recorded as part of the producer version", order = 50)
	private String model = DEFAULT_MODEL;

	/**
	 * Empty means "every category the family knows".
	 *
	 * <p>
	 * Narrowing it is a throughput decision for the per-policy families: ShieldGemma and Granite
	 * Guardian issue one backend call <em>per selected category</em>, so dropping from nine criteria
	 * to two makes the node four times faster. It makes no difference to Llama Guard, which answers
	 * about everything in one pass either way.
	 * </p>
	 */
	@ParamDoc(label = "Categories", type = ParameterType.ENUM_SET, order = 60,
		description = "Harm categories to check, using the selected family's own codes. Empty checks all of them",
		// The union of all five vocabularies, because a descriptor parameter's allowed values are
		// static while these depend on the family selected in the field above it. Picking a code
		// from the wrong family is caught by validate() with a message naming the right ones - a
		// worse experience than a family-aware picker, but a far better one than an empty list.
		values = {
			"S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12", "S13", "S14",
			"dangerous_content", "harassment", "hate_speech", "sexually_explicit", "violence_gore",
			"harm", "social_bias", "jailbreak", "violence", "profanity", "sexual_content", "unethical_behavior",
			"harm_engagement", "evasiveness" })
	private List<String> categories = List.of();

	@ParamDoc(label = "Threshold", description = "P(unsafe) at or above which the item is flagged", min = "0.0", max = "1.0", step = "0.01",
		order = 70)
	private double threshold = DEFAULT_THRESHOLD;

	@ParamDoc(label = "Max Characters", description = "Upper bound on the text sent to the model; longer input is truncated", min = "1",
		order = 80)
	private int maxChars = DEFAULT_MAX_CHARS;

	@ParamDoc(label = "Max Image Dimension", description = "Longest side an image is downscaled to before it is sent", min = "64", order = 90)
	private int maxImageDim = DEFAULT_MAX_IMAGE_DIM;

	@ParamDoc(label = "API Key", description = "Bearer token for the endpoint. Leave empty for a local llama.cpp or vLLM", order = 115)
	private String apiKey;

	/**
	 * Replace the family's built-in prompt entirely.
	 *
	 * <p>
	 * Empty — the default — uses the prompt the model was tuned against, which is almost always what
	 * you want; these are classifiers, not chat models, and they are sensitive to their own wording.
	 * It exists for the case a new checkpoint changes its template before this node catches up.
	 * Placeholders are {@code ${text}} and, depending on the family, {@code ${categories}} (Llama
	 * Guard) or {@code ${guideline}} (ShieldGemma, Granite Guardian).
	 * </p>
	 */
	// language="text" only sets the editor's placeholder, which is `// <language>` and defaults to
	// `// javascript`. A prompt template is not javascript, and the field is empty by default, so
	// that placeholder is the first thing an author reads about this parameter.
	@ParamDoc(label = "Prompt Template", type = ParameterType.CODE, rows = 12, language = "text",
		description = "Override the family's built-in prompt. Empty uses the prompt the model was tuned against", order = 120)
	private String promptTemplate;

	public GuardFamily getFamily() {
		return family;
	}

	public GuardNodeOptions setFamily(GuardFamily family) {
		this.family = family;
		return this;
	}

	public String getModel() {
		return model;
	}

	public GuardNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public List<String> getCategories() {
		return categories;
	}

	public GuardNodeOptions setCategories(List<String> categories) {
		this.categories = categories == null ? List.of() : List.copyOf(categories);
		return this;
	}

	public double getThreshold() {
		return threshold;
	}

	public GuardNodeOptions setThreshold(double threshold) {
		this.threshold = threshold;
		return this;
	}

	public int getMaxChars() {
		return maxChars;
	}

	public GuardNodeOptions setMaxChars(int maxChars) {
		this.maxChars = maxChars;
		return this;
	}

	public int getMaxImageDim() {
		return maxImageDim;
	}

	public GuardNodeOptions setMaxImageDim(int maxImageDim) {
		this.maxImageDim = maxImageDim;
		return this;
	}

	public String getApiKey() {
		return apiKey;
	}

	public GuardNodeOptions setApiKey(String apiKey) {
		this.apiKey = apiKey;
		return this;
	}

	public String getPromptTemplate() {
		return promptTemplate;
	}

	public GuardNodeOptions setPromptTemplate(String promptTemplate) {
		this.promptTemplate = promptTemplate;
		return this;
	}

	/**
	 * The category codes to probe: the configured selection, or the family's whole vocabulary when
	 * none was configured.
	 *
	 * @return the codes in taxonomy order, never empty
	 */
	public List<String> effectiveCategories() {
		if (categories == null || categories.isEmpty()) {
			return GuardTaxonomy.codes(family);
		}
		// Reordered into taxonomy order rather than kept as configured, so the rendered category
		// block is stable no matter how the author happened to tick the boxes - a prompt that
		// changes with the click order would defeat the result cache.
		return GuardTaxonomy.codes(family).stream().filter(categories::contains).toList();
	}

	@Override
	protected GuardNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		errors.addAll(validateEndpoint());

		if (family == null) {
			errors.add("family must not be empty");
		}
		if (model == null || model.isBlank()) {
			errors.add("model must not be empty");
		}
		if (threshold < 0d || threshold > 1d) {
			errors.add("threshold must be between 0.0 and 1.0, got " + threshold);
		}
		if (maxChars < 1) {
			errors.add("maxChars must be at least 1, got " + maxChars);
		}
		if (maxImageDim < 64) {
			errors.add("maxImageDim must be at least 64, got " + maxImageDim);
		}
		if (family != null && categories != null) {
			for (String code : categories) {
				// Rejected here rather than tolerated at runtime: a typo'd code would silently drop
				// a category the operator believes is being checked, which is the worst way for a
				// content guard to fail. No separate "selects nothing" check is needed - the
				// selection is empty exactly when every code was unknown, and each of those is
				// already reported here.
				if (!GuardTaxonomy.isKnown(family, code)) {
					errors.add("categories contains '" + code + "', which " + family + " does not know. Known codes: "
						+ String.join(", ", GuardTaxonomy.codes(family)));
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
