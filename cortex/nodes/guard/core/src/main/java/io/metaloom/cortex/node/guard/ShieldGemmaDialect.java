package io.metaloom.cortex.node.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.node.guard.GuardTaxonomy.NativeCategory;

/**
 * Google ShieldGemma 1 (text) and ShieldGemma 2 (image).
 *
 * <p>
 * A per-policy family: the prompt asks about exactly one guideline and the model answers
 * {@code Yes} or {@code No}, so one verdict costs one call <em>per selected category</em>. Four
 * selected policies mean four calls for a single asset, which is the trade an operator makes for
 * genuinely independent per-category scores.
 * </p>
 *
 * <p>
 * ShieldGemma is a generative model used in scoring mode — Google's model card is explicit that the
 * answer is the renormalised probability of the {@code Yes} token, not the generated word. That is
 * what {@link GuardScoring} produces, and it is why a backend that will not return log probabilities
 * degrades the node to a coarse 1/0 rather than breaking it.
 * </p>
 *
 * <p>
 * The guideline sentences are Google's own, reproduced from the ShieldGemma model cards. They are
 * part of the prompt the model was tuned against, so paraphrasing them costs accuracy; an operator
 * who needs different policy wording should override {@code promptTemplate} deliberately rather
 * than by accident.
 * </p>
 */
public class ShieldGemmaDialect implements GuardDialect {

	/** The decision is the first token; two leaves room for a leading newline. */
	private static final int MAX_TOKENS = 2;

	private static final List<String> UNSAFE_TOKENS = List.of("Yes");

	private static final List<String> SAFE_TOKENS = List.of("No");

	/** ShieldGemma 1, text. The {@code <start_of_turn>} markers are part of the prompt, not a chat wrapper. */
	private static final String TEXT_TEMPLATE = """
		You are a policy expert trying to help determine whether a user prompt is in violation of the defined safety policies.

		<start_of_turn>
		Human Question: ${text}
		<end_of_turn>

		Our safety principle is defined in the below:

		* ${guideline}

		===

		Does the human question violate the above principle? Your answer must start with 'Yes' or 'No'. And then walk through step by step to be sure we answer correctly.

		""";

	/** ShieldGemma 2, image. The image itself is attached as a separate content part. */
	private static final String IMAGE_TEMPLATE = """
		You are a policy expert trying to help determine whether a generated or real image is in violation of the safety policy below.

		Our safety principle is defined in the below:

		* ${guideline}

		===

		Does the image violate the above principle? Your answer must start with 'Yes' or 'No'. And then walk through step by step to be sure we answer correctly.

		""";

	private static final Map<String, String> TEXT_GUIDELINES = Map.of(
		"dangerous_content", "\"No Dangerous Content\": The prompt shall not contain or seek generation of content that "
			+ "harming oneself and/or others (e.g., accessing or building firearms and explosive devices, promotion of "
			+ "terrorism, instructions for suicide).",
		"harassment", "\"No Harassment\": The prompt shall not contain or seek generation of content that is malicious, "
			+ "intimidating, bullying, or abusive content targeting another individual (e.g., physical threats, denial of "
			+ "tragic events, disparaging victims of violence).",
		"hate_speech", "\"No Hate Speech\": The prompt shall not contain or seek generation of content that targeting "
			+ "identity and/or protected attributes (e.g., racial slurs, promotion of discrimination, calls to violence "
			+ "against protected groups) or dehumanizing/belittling/vilifying on the bases of race, ethnicity, religion, "
			+ "disability, age, nationality, veteran status, sexual orientation, gender, gender identity, caste, or any "
			+ "other protected status.",
		"sexually_explicit", "\"No Sexually Explicit Information\": The prompt shall not contain or seek generation of "
			+ "content that contains references to sexual acts or other lewd content (e.g., sexually graphic descriptions, "
			+ "content aimed at causing arousal). Medical or scientific terms related to human anatomy or sex education are "
			+ "permitted.");

	private static final Map<String, String> IMAGE_GUIDELINES = Map.of(
		"sexually_explicit", "\"No Sexually Explicit content\": The image shall not contain content that depicts explicit "
			+ "or graphic sexual acts (e.g., pornography, erotic nudity, depictions of rape or sexual assault).",
		"violence_gore", "\"No Violence/Gore content\": The image shall not contain content that depicts shocking, "
			+ "sensational, or gratuitous violence (e.g., excessive blood and gore, gratuitous violence against animals, "
			+ "extreme injury or moment of death).",
		"dangerous_content", "\"No Dangerous Content\": The image shall not contain content that facilitates or encourages "
			+ "activities that could cause real-world harm (e.g., building firearms and explosive devices, promotion of "
			+ "terrorism, instructions for suicide).");

	private final GuardFamily family;

	public ShieldGemmaDialect(GuardFamily family) {
		this.family = family;
	}

	@Override
	public List<GuardProbe> textProbes(String text, List<String> codes, GuardNodeOptions options) {
		String template = template(options, TEXT_TEMPLATE);
		List<GuardProbe> probes = new ArrayList<>();
		for (String code : codes) {
			probes.add(GuardProbe.of(code, template
				.replace("${guideline}", guideline(TEXT_GUIDELINES, code))
				.replace("${text}", text), MAX_TOKENS));
		}
		return List.copyOf(probes);
	}

	@Override
	public List<GuardProbe> imageProbes(List<String> codes, GuardNodeOptions options) {
		String template = template(options, IMAGE_TEMPLATE);
		List<GuardProbe> probes = new ArrayList<>();
		for (String code : codes) {
			probes.add(GuardProbe.of(code, template
				.replace("${guideline}", guideline(IMAGE_GUIDELINES, code))
				// The image path carries no text; leaving the placeholder in would send the model
				// the literal string "${text}".
				.replace("${text}", ""), MAX_TOKENS));
		}
		return List.copyOf(probes);
	}

	@Override
	public GuardProbeResult parse(GuardProbe probe, GuardCompletion completion, GuardNodeOptions options) {
		GuardScoring.Score score = GuardScoring.score(completion, UNSAFE_TOKENS, SAFE_TOKENS);
		List<GuardVerdict.Hit> hits = List.of();
		if (score.value() >= options.getThreshold()) {
			NativeCategory category = GuardTaxonomy.resolve(family, probe.nativeCode());
			hits = List.of(new GuardVerdict.Hit(category.canonical(), category.code(), category.label(), score.value()));
		}
		return new GuardProbeResult(score.value(), score.exact(), hits, completion.text());
	}

	/**
	 * The policy sentence for a code. An unknown code — reachable only through a hand-edited worker
	 * config, since options validation rejects one — gets a generic sentence built from the taxonomy
	 * label rather than an exception, so one typo does not take the whole asset down.
	 */
	private String guideline(Map<String, String> guidelines, String code) {
		String known = guidelines.get(code);
		if (known != null) {
			return known;
		}
		return "\"No " + GuardTaxonomy.resolve(family, code).label() + "\": The content shall not contain or seek generation of "
			+ GuardTaxonomy.resolve(family, code).label().toLowerCase() + ".";
	}

	private String template(GuardNodeOptions options, String builtIn) {
		String override = options.getPromptTemplate();
		return override != null && !override.isBlank() ? override : builtIn;
	}
}
