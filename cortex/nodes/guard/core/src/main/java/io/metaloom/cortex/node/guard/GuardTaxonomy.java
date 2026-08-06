package io.metaloom.cortex.node.guard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native harm vocabulary of each guard family, and its mapping onto {@link GuardCategory}.
 *
 * <p>
 * One table per family <em>and version</em>. Llama Guard 3 and 4 share thirteen of their fourteen
 * codes and differ in the last one, which is exactly the kind of near-identity that invites a shared
 * table with an exception in it; keeping them separate means a future Llama Guard 5 is a new table
 * rather than a new special case in an old one.
 * </p>
 *
 * <p>
 * Order matters: it is the order the codes are listed in the model's own prompt (for Llama Guard) and
 * the order probes are issued in (for the per-policy families), so it is the order an operator sees
 * in the editor's category picker.
 * </p>
 */
public final class GuardTaxonomy {

	/**
	 * One entry of a family's native vocabulary.
	 *
	 * @param code      the family's own identifier — {@code S3}, {@code hate_speech}, {@code jailbreak}
	 * @param label     the family's own name for it, reproduced verbatim in the model prompt
	 * @param canonical where it lands in the shared vocabulary
	 */
	public record NativeCategory(String code, String label, GuardCategory canonical) {
	}

	private static final Map<GuardFamily, Map<String, NativeCategory>> TABLES = new LinkedHashMap<>();

	static {
		// Meta's MLCommons hazard taxonomy. The labels are the ones Llama Guard's own prompt uses,
		// because the prompt we render has to reproduce them exactly - the model was trained on them.
		register(GuardFamily.LLAMA_GUARD_3,
			cat("S1", "Violent Crimes", GuardCategory.VIOLENT_CRIME),
			cat("S2", "Non-Violent Crimes", GuardCategory.NON_VIOLENT_CRIME),
			cat("S3", "Sex Crimes", GuardCategory.SEX_CRIME),
			cat("S4", "Child Exploitation", GuardCategory.CHILD_EXPLOITATION),
			cat("S5", "Defamation", GuardCategory.DEFAMATION),
			cat("S6", "Specialized Advice", GuardCategory.SPECIALIZED_ADVICE),
			cat("S7", "Privacy", GuardCategory.PRIVACY),
			cat("S8", "Intellectual Property", GuardCategory.INTELLECTUAL_PROPERTY),
			cat("S9", "Indiscriminate Weapons", GuardCategory.INDISCRIMINATE_WEAPONS),
			cat("S10", "Hate", GuardCategory.HATE),
			cat("S11", "Self-Harm", GuardCategory.SELF_HARM),
			cat("S12", "Sexual Content", GuardCategory.SEXUAL_CONTENT),
			cat("S13", "Elections", GuardCategory.ELECTIONS),
			cat("S14", "Code Interpreter Abuse", GuardCategory.CODE_INTERPRETER_ABUSE));

		// Llama Guard 4 dropped S14; everything below it kept its number. Enumerated rather than
		// derived from the 3 table, so the day Meta renumbers something this file is where it shows.
		register(GuardFamily.LLAMA_GUARD_4,
			cat("S1", "Violent Crimes", GuardCategory.VIOLENT_CRIME),
			cat("S2", "Non-Violent Crimes", GuardCategory.NON_VIOLENT_CRIME),
			cat("S3", "Sex Crimes", GuardCategory.SEX_CRIME),
			cat("S4", "Child Exploitation", GuardCategory.CHILD_EXPLOITATION),
			cat("S5", "Defamation", GuardCategory.DEFAMATION),
			cat("S6", "Specialized Advice", GuardCategory.SPECIALIZED_ADVICE),
			cat("S7", "Privacy", GuardCategory.PRIVACY),
			cat("S8", "Intellectual Property", GuardCategory.INTELLECTUAL_PROPERTY),
			cat("S9", "Indiscriminate Weapons", GuardCategory.INDISCRIMINATE_WEAPONS),
			cat("S10", "Hate", GuardCategory.HATE),
			cat("S11", "Self-Harm", GuardCategory.SELF_HARM),
			cat("S12", "Sexual Content", GuardCategory.SEXUAL_CONTENT),
			cat("S13", "Elections", GuardCategory.ELECTIONS));

		// ShieldGemma's four text policies.
		register(GuardFamily.SHIELDGEMMA,
			cat("dangerous_content", "Dangerous Content", GuardCategory.DANGEROUS_CONTENT),
			cat("harassment", "Harassment", GuardCategory.HARASSMENT),
			cat("hate_speech", "Hate Speech", GuardCategory.HATE),
			cat("sexually_explicit", "Sexually Explicit Information", GuardCategory.SEXUAL_CONTENT));

		// ShieldGemma 2's three image policies. 'violence_gore' has no counterpart in the text
		// model and maps onto VIOLENT_CRIME, the closest canonical bucket.
		register(GuardFamily.SHIELDGEMMA_2,
			cat("sexually_explicit", "Sexually Explicit", GuardCategory.SEXUAL_CONTENT),
			cat("violence_gore", "Violence & Gore", GuardCategory.VIOLENT_CRIME),
			cat("dangerous_content", "Dangerous Content", GuardCategory.DANGEROUS_CONTENT));

		// Granite Guardian's content-harm criteria. The RAG criteria (groundedness,
		// context_relevance, answer_relevance) and the agentic one (function_call) are deliberately
		// absent: they judge a generated answer against a source document, which is not what this
		// node has. Adding them means giving the node a second text input, not a table row.
		register(GuardFamily.GRANITE_GUARDIAN,
			cat("harm", "Harm", GuardCategory.OTHER),
			cat("social_bias", "Social Bias", GuardCategory.HATE),
			cat("jailbreak", "Jailbreak", GuardCategory.JAILBREAK),
			cat("violence", "Violence", GuardCategory.VIOLENT_CRIME),
			cat("profanity", "Profanity", GuardCategory.PROFANITY),
			cat("sexual_content", "Sexual Content", GuardCategory.SEXUAL_CONTENT),
			cat("unethical_behavior", "Unethical Behaviour", GuardCategory.UNETHICAL_BEHAVIOUR),
			cat("harm_engagement", "Harm Engagement", GuardCategory.OTHER),
			cat("evasiveness", "Evasiveness", GuardCategory.OTHER));
	}

	private GuardTaxonomy() {
	}

	/**
	 * Every native category the family knows, in the family's own order.
	 *
	 * @param family the guard family
	 * @return the vocabulary, never empty
	 */
	public static List<NativeCategory> categories(GuardFamily family) {
		return List.copyOf(table(family).values());
	}

	/**
	 * Every native code the family knows, in the family's own order. This is what the options'
	 * {@code categories} field is validated against and what the editor offers.
	 *
	 * @param family the guard family
	 * @return the codes, never empty
	 */
	public static List<String> codes(GuardFamily family) {
		return List.copyOf(table(family).keySet());
	}

	/**
	 * Resolve one native code.
	 *
	 * <p>
	 * An unknown code is <strong>not</strong> an error: a model revision that introduces a new hazard
	 * would otherwise turn every flagged item into a node failure. It comes back as itself, mapped to
	 * {@link GuardCategory#OTHER}, so the verdict still carries the truth the model told us.
	 * </p>
	 *
	 * @param family the guard family
	 * @param code   the family's own identifier
	 * @return the resolved category, never null
	 */
	public static NativeCategory resolve(GuardFamily family, String code) {
		NativeCategory known = table(family).get(code);
		return known != null ? known : new NativeCategory(code, code, GuardCategory.OTHER);
	}

	/**
	 * Whether the family knows this code. Used by options validation, which should reject a typo at
	 * configuration time even though {@link #resolve} tolerates one at runtime.
	 *
	 * @param family the guard family
	 * @param code   the family's own identifier
	 * @return true when the code is part of the family's vocabulary
	 */
	public static boolean isKnown(GuardFamily family, String code) {
		return table(family).containsKey(code);
	}

	private static Map<String, NativeCategory> table(GuardFamily family) {
		Map<String, NativeCategory> table = TABLES.get(family);
		if (table == null) {
			// Only reachable by adding an enum constant without a table, which GuardTaxonomyTest catches.
			throw new IllegalStateException("No taxonomy registered for guard family " + family);
		}
		return table;
	}

	private static void register(GuardFamily family, NativeCategory... categories) {
		// LinkedHashMap wrapped unmodifiable rather than Map.copyOf: the latter is immutable but
		// unordered, and the declaration order here is the order the editor and the prompt use.
		Map<String, NativeCategory> table = new LinkedHashMap<>();
		for (NativeCategory category : categories) {
			table.put(category.code(), category);
		}
		TABLES.put(family, Collections.unmodifiableMap(table));
	}

	private static NativeCategory cat(String code, String label, GuardCategory canonical) {
		return new NativeCategory(code, label, canonical);
	}
}
