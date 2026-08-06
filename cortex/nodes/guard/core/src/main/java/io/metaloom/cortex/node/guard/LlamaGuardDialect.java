package io.metaloom.cortex.node.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.metaloom.cortex.node.guard.GuardTaxonomy.NativeCategory;

/**
 * Meta Llama Guard 3 and 4.
 *
 * <p>
 * The odd one out of the three families, and the reason {@link GuardProbe} carries a nullable
 * category: Llama Guard classifies against its whole taxonomy in a <strong>single</strong> forward
 * pass. One call per item regardless of how many categories are selected, where ShieldGemma and
 * Granite Guardian need one call each. That makes it the cheap choice for broad screening, at the
 * cost of a single probability covering every category it names — see {@link GuardVerdict}.
 * </p>
 *
 * <p>
 * The category block is assembled from the selected codes with their original numbers intact.
 * Renumbering a narrowed selection would be the intuitive thing to do and would be wrong: the model
 * learned {@code S4} <em>means</em> child exploitation, so a run selecting only two categories and
 * calling them {@code S1}/{@code S2} gets answers about violent and non-violent crime.
 * </p>
 *
 * <p>
 * The prompt reproduces Meta's published Llama Guard template, special tokens and all, because we
 * send it to the raw completions endpoint rather than letting the backend apply a chat template
 * (see {@link GuardDialect}). Llama Guard 4 is built on Llama 4 and uses that generation's header
 * tokens, which is why the two versions differ by more than their category list.
 * </p>
 */
public class LlamaGuardDialect implements GuardDialect {

	/** Enough for {@code unsafe\nS1,S10,S12} and not enough for an essay. */
	private static final int MAX_TOKENS = 24;

	private static final List<String> UNSAFE_TOKENS = List.of("unsafe");

	private static final List<String> SAFE_TOKENS = List.of("safe");

	/** The hazard codes in the answer's second line. */
	private static final Pattern CODE = Pattern.compile("\\bS(\\d{1,2})\\b");

	/** The instruction body, shared by both versions and by the text and image paths. */
	private static final String TASK = """
		Task: Check if there is unsafe content in 'User' messages in conversations according our safety policy with the below categories.

		<BEGIN UNSAFE CONTENT CATEGORIES>
		${categories}
		<END UNSAFE CONTENT CATEGORIES>

		<BEGIN CONVERSATION>

		User: ${text}

		<END CONVERSATION>

		Provide your safety assessment for ONLY THE LAST User message in the above conversation:
		 - First line must read 'safe' or 'unsafe'.
		 - If unsafe, a second line must include a comma-separated list of violated categories.""";

	/** Llama 3 header tokens. */
	private static final String LLAMA_3_TEMPLATE =
		"<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n" + TASK + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";

	/** Llama 4 renamed every header token; the body is unchanged. */
	private static final String LLAMA_4_TEMPLATE =
		"<|begin_of_text|><|header_start|>user<|header_end|>\n\n" + TASK + "<|eot|><|header_start|>assistant<|header_end|>\n\n";

	private final GuardFamily family;

	public LlamaGuardDialect(GuardFamily family) {
		this.family = family;
	}

	@Override
	public List<GuardProbe> textProbes(String text, List<String> codes, GuardNodeOptions options) {
		String prompt = template(options)
			.replace("${categories}", categoryBlock(codes))
			.replace("${text}", text);
		return List.of(GuardProbe.all(prompt, MAX_TOKENS));
	}

	@Override
	public List<GuardProbe> imageProbes(List<String> codes, GuardNodeOptions options) {
		// No special tokens here: the image path goes through chat completions, which is the only
		// endpoint that accepts an image part, and there the backend applies the model's own
		// template. Sending header tokens as well would nest one template inside another.
		String prompt = TASK
			.replace("${categories}", categoryBlock(codes))
			.replace("${text}", "");
		return List.of(GuardProbe.all(prompt, MAX_TOKENS));
	}

	@Override
	public GuardProbeResult parse(GuardProbe probe, GuardCompletion completion, GuardNodeOptions options) {
		GuardScoring.Score score = GuardScoring.score(completion, UNSAFE_TOKENS, SAFE_TOKENS);
		boolean flagged = score.value() >= options.getThreshold();

		List<GuardVerdict.Hit> hits = new ArrayList<>();
		if (flagged) {
			for (String code : codesIn(completion.text())) {
				NativeCategory category = GuardTaxonomy.resolve(family, code);
				hits.add(new GuardVerdict.Hit(category.canonical(), category.code(), category.label(), score.value()));
			}
			if (hits.isEmpty()) {
				// The model said unsafe and named nothing - rare, but the verdict must still say
				// something rather than report a flagged item with an empty category list.
				hits.add(new GuardVerdict.Hit(GuardCategory.OTHER, "unspecified", "Unspecified", score.value()));
			}
		}
		return new GuardProbeResult(score.value(), score.exact(), List.copyOf(hits), completion.text());
	}

	/**
	 * The {@code Sn: Label.} lines the model expects, in taxonomy order.
	 */
	private String categoryBlock(List<String> codes) {
		StringBuilder sb = new StringBuilder();
		for (String code : codes) {
			NativeCategory category = GuardTaxonomy.resolve(family, code);
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			sb.append(category.code()).append(": ").append(category.label()).append('.');
		}
		return sb.toString();
	}

	/** The distinct hazard codes named in the answer, in the order the model listed them. */
	private Set<String> codesIn(String text) {
		Set<String> codes = new LinkedHashSet<>();
		if (text == null) {
			return codes;
		}
		Matcher matcher = CODE.matcher(text);
		while (matcher.find()) {
			codes.add("S" + Integer.parseInt(matcher.group(1)));
		}
		return codes;
	}

	private String template(GuardNodeOptions options) {
		String override = options.getPromptTemplate();
		if (override != null && !override.isBlank()) {
			return override;
		}
		return family == GuardFamily.LLAMA_GUARD_4 ? LLAMA_4_TEMPLATE : LLAMA_3_TEMPLATE;
	}
}
