package io.metaloom.cortex.node.guard;

import java.util.List;

/**
 * What one guard model family expects to be asked, and what its answer means.
 *
 * <p>
 * This is the seam that makes a single {@code guard} node cover Llama Guard, ShieldGemma and Granite
 * Guardian. Everything family-specific lives behind it — how many calls one verdict takes, what the
 * prompt looks like, which tokens carry the decision, how a flagged category is named — and
 * everything else in the node is written against {@link GuardProbe} and {@link GuardProbeResult}
 * only. A fourth family is one class and one {@link GuardTaxonomy} table.
 * </p>
 *
 * <p>
 * Implementations are pure: string in, string out, no network and no state. That is deliberate, and
 * it is what lets {@code GuardDialectTest} pin every prompt and every documented answer shape
 * without a GPU.
 * </p>
 *
 * <h2>Why the node renders the prompt itself</h2>
 *
 * <p>
 * The obvious alternative is to POST a chat conversation and let the backend apply the model's own
 * chat template. It does not survive contact with these three: ShieldGemma's template needs a
 * {@code guideline} argument and Granite Guardian's needs a {@code guardian_config} object, both
 * passed as extra template keyword arguments that a backend has to explicitly forward. Rendering
 * here means one code path that works against llama.cpp, vLLM and Ollama alike, and it means the
 * exact bytes sent to the model are visible in a unit test.
 * </p>
 */
public interface GuardDialect {

	/**
	 * The calls needed to reach one verdict about this text.
	 *
	 * @param text     the prose to classify
	 * @param codes    the native category codes the operator selected, in taxonomy order
	 * @param options  the node configuration
	 * @return one or more probes, never empty
	 */
	List<GuardProbe> textProbes(String text, List<String> codes, GuardNodeOptions options);

	/**
	 * The calls needed to reach one verdict about an image. Only called for a family whose
	 * {@link GuardFamily#supportsImages()} is true; the prompt is the text part of a multimodal
	 * request and the image is attached alongside it by {@link GuardClient}.
	 *
	 * @param codes   the native category codes the operator selected, in taxonomy order
	 * @param options the node configuration
	 * @return one or more probes, never empty
	 */
	List<GuardProbe> imageProbes(List<String> codes, GuardNodeOptions options);

	/**
	 * Interpret one answer.
	 *
	 * @param probe      the probe that produced it
	 * @param completion the backend answer
	 * @param options    the node configuration
	 * @return the normalised result
	 */
	GuardProbeResult parse(GuardProbe probe, GuardCompletion completion, GuardNodeOptions options);

	/**
	 * The dialect for a family.
	 *
	 * @param family the guard family
	 * @return the dialect, never null
	 */
	static GuardDialect of(GuardFamily family) {
		return switch (family) {
			case LLAMA_GUARD_3, LLAMA_GUARD_4 -> new LlamaGuardDialect(family);
			case SHIELDGEMMA, SHIELDGEMMA_2 -> new ShieldGemmaDialect(family);
			case GRANITE_GUARDIAN -> new GraniteGuardianDialect();
		};
	}
}
