package io.metaloom.cortex.node.guard;

/**
 * The canonical harm vocabulary — the common denominator the three guard model families are
 * normalised into.
 *
 * <p>
 * It is the union of the three native taxonomies rather than an intersection, because an
 * intersection would be almost empty: Llama Guard follows the MLCommons hazard list
 * ({@code S1}…{@code S14}), ShieldGemma has four broad content policies, and Granite Guardian mixes
 * content harms with agentic risks like {@code jailbreak}. A pipeline author who routes on
 * {@link #SEXUAL_CONTENT} should keep working after the operator swaps Llama Guard for ShieldGemma,
 * and that is only possible if every family lands in the same vocabulary.
 * </p>
 *
 * <p>
 * The native code is <em>not</em> discarded when the mapping happens — {@code GuardVerdict} carries
 * both, so a downstream consumer that genuinely cares about {@code S6 Specialized Advice} can still
 * see it. The mapping tables live in {@link GuardTaxonomy}.
 * </p>
 */
public enum GuardCategory {

	VIOLENT_CRIME("Violent Crimes"),

	NON_VIOLENT_CRIME("Non-Violent Crimes"),

	SEX_CRIME("Sex-Related Crimes"),

	CHILD_EXPLOITATION("Child Sexual Exploitation"),

	DEFAMATION("Defamation"),

	SPECIALIZED_ADVICE("Specialized Advice"),

	PRIVACY("Privacy"),

	INTELLECTUAL_PROPERTY("Intellectual Property"),

	INDISCRIMINATE_WEAPONS("Indiscriminate Weapons"),

	HATE("Hate"),

	SELF_HARM("Suicide & Self-Harm"),

	SEXUAL_CONTENT("Sexual Content"),

	ELECTIONS("Elections"),

	CODE_INTERPRETER_ABUSE("Code Interpreter Abuse"),

	HARASSMENT("Harassment"),

	DANGEROUS_CONTENT("Dangerous Content"),

	PROFANITY("Profanity"),

	UNETHICAL_BEHAVIOUR("Unethical Behaviour"),

	JAILBREAK("Jailbreak"),

	/**
	 * The catch-all. Reached two ways: a family's general-purpose criterion that names no specific
	 * harm (Granite Guardian's {@code harm}), and a native code this build does not know — a model
	 * revision that adds an {@code S15} must not make the node fail, so an unmapped code lands here
	 * with its native value preserved.
	 */
	OTHER("Other");

	private final String label;

	GuardCategory(String label) {
		this.label = label;
	}

	/** Human-readable name, carried onto the verdict so a UI needs no second lookup table. */
	public String label() {
		return label;
	}
}
