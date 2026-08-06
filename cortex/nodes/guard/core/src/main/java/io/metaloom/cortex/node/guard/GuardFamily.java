package io.metaloom.cortex.node.guard;

/**
 * Which guard model the node is pointed at.
 *
 * <p>
 * The version is part of the constant rather than a separate option because it changes the
 * <em>contract</em>, not just the weights: Llama Guard 3 has fourteen hazard codes and Llama Guard 4
 * has thirteen ({@code S14 Code Interpreter Abuse} is gone), and ShieldGemma 2 moderates images
 * against three policies while ShieldGemma 1 moderates text against four. Folding those into one
 * {@code LLAMA_GUARD} constant would mean the node guessing which taxonomy applies from the model
 * id string.
 * </p>
 *
 * <p>
 * The enum name is what a pipeline author picks in the editor, so it is also what
 * {@code GuardNodeOptions.family} serialises as.
 * </p>
 */
public enum GuardFamily {

	/** Meta Llama Guard 3 (1B/8B, text). One call classifies against all fourteen hazards at once. */
	LLAMA_GUARD_3(false),

	/** Meta Llama Guard 4 12B — natively multimodal, thirteen hazards, one call. */
	LLAMA_GUARD_4(true),

	/** Google ShieldGemma 2b/9b/27b (text). One call <em>per policy</em>. */
	SHIELDGEMMA(false),

	/** Google ShieldGemma 2 4B — image moderation against three policies, one call per policy. */
	SHIELDGEMMA_2(true),

	/** IBM Granite Guardian 3.x (text). One call per criterion. */
	GRANITE_GUARDIAN(false);

	private final boolean supportsImages;

	GuardFamily(boolean supportsImages) {
		this.supportsImages = supportsImages;
	}

	/**
	 * Whether this family can look at pixels.
	 *
	 * <p>
	 * Note that this describes the <em>model</em>, not the backend. Neither multimodal family can be
	 * served by llama.cpp today — no {@code mmproj} projector has been published for Llama Guard 4,
	 * and {@code shieldgemma-2-4b} has no GGUF conversion at all — so the image path in practice
	 * means vLLM. The node reports that as a failure rather than guessing.
	 * </p>
	 *
	 * @return true when the family accepts an image
	 */
	public boolean supportsImages() {
		return supportsImages;
	}
}
