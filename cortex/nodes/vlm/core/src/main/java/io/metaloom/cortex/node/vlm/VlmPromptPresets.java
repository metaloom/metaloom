package io.metaloom.cortex.node.vlm;

/**
 * Ready-made {@link VlmNodePrompt} configurations for models whose prompt and output format are fixed by the model itself.
 */
public final class VlmPromptPresets {

	/** Prompt id under which the olmOCR preset is registered by default. */
	public static final String OLMOCR_ID = "olmocr";

	/** Default model id for the olmOCR preset. */
	public static final String OLMOCR_MODEL = "allenai/olmOCR-2-7B-1025-FP8";

	/**
	 * The olmOCR page prompt, matching {@code build_no_anchoring_v4_yaml_prompt()} in the upstream olmocr toolkit. olmOCR-2 is trained against this exact
	 * wording, so it is not a knob to tune - changing it degrades the transcription.
	 */
	public static final String OLMOCR_PROMPT = "Attached is one page of a document that you must process. "
		+ "Just return the plain text representation of this document as if you were reading it naturally. "
		+ "Convert equations to LateX and tables to HTML.\n"
		+ "If there are any figures or charts, label them with the following markdown syntax "
		+ "![Alt text describing the contents of the figure](page_startx_starty_width_height.png)\n"
		+ "Return your output as markdown, with a front matter section on top specifying values for the "
		+ "primary_language, is_rotation_valid, rotation_correction, is_table, and is_diagram parameters.";

	/**
	 * Longest image side olmOCR-2 is trained on. Feeding it larger pages wastes vision tokens without improving accuracy; smaller ones lose glyph detail.
	 */
	public static final int OLMOCR_MAX_IMAGE_DIM = 1288;

	private VlmPromptPresets() {
	}

	/**
	 * The olmOCR document-transcription preset, pointed at {@link #OLMOCR_MODEL}.
	 */
	public static VlmNodePrompt olmOcr() {
		return olmOcr(OLMOCR_MODEL);
	}

	/**
	 * The olmOCR document-transcription preset for a specific model id (vLLM serves whatever id it was started with).
	 */
	public static VlmNodePrompt olmOcr(String model) {
		return new VlmNodePrompt()
			.setModel(model)
			.setPrompt(OLMOCR_PROMPT)
			.setResponseFormat(VlmResponseFormat.OLMOCR)
			.setMaxImageDim(OLMOCR_MAX_IMAGE_DIM)
			.setMaxTokens(VlmNodePrompt.DEFAULT_MAX_TOKENS)
			.setTemperature(0.1)
			.setRetryOnRotation(true);
	}
}
