package io.metaloom.cortex.node.guard;

/**
 * One question put to the guard model.
 *
 * <p>
 * The number of probes per item is the sharpest difference between the families and the reason this
 * type exists at all. Llama Guard classifies against its whole taxonomy in a single forward pass, so
 * it issues <em>one</em> probe whose {@code nativeCode} is null. ShieldGemma and Granite Guardian
 * answer one yes/no question at a time, so they issue one probe <em>per selected category</em> — six
 * selected criteria mean six calls to the backend for one asset. An operator who narrows
 * {@code categories} is buying throughput, and it is worth saying so in the node's docs.
 * </p>
 *
 * @param nativeCode the category this probe asks about, or null when the probe covers all of them
 * @param prompt     the fully rendered prompt, ready to be sent verbatim
 * @param maxTokens  how much the model is allowed to say; small on purpose, since every family's
 *                   decision is in its first token or two
 */
public record GuardProbe(String nativeCode, String prompt, int maxTokens) {

	/**
	 * A probe covering the family's whole taxonomy in one call.
	 *
	 * @param prompt    the rendered prompt
	 * @param maxTokens output budget
	 * @return the probe
	 */
	public static GuardProbe all(String prompt, int maxTokens) {
		return new GuardProbe(null, prompt, maxTokens);
	}

	/**
	 * A probe asking about one category.
	 *
	 * @param nativeCode the family's own identifier for the category
	 * @param prompt     the rendered prompt
	 * @param maxTokens  output budget
	 * @return the probe
	 */
	public static GuardProbe of(String nativeCode, String prompt, int maxTokens) {
		return new GuardProbe(nativeCode, prompt, maxTokens);
	}
}
