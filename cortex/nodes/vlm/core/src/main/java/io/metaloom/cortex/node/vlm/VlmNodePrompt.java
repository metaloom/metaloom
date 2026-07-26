package io.metaloom.cortex.node.vlm;

/**
 * One named vision-language task: the model to ask, what to ask it, how to prepare the image and how to read the answer back.
 *
 * <p>
 * A node can carry several of these (keyed by prompt id in {@link VlmNodeOptions#getPrompts()}), so a single VLM endpoint can serve OCR, captioning and
 * ad-hoc extraction in one pass over the image.
 * </p>
 */
public class VlmNodePrompt {

	/** Default output token budget. A full document page of text needs a few thousand tokens. */
	public static final int DEFAULT_MAX_TOKENS = 4096;

	/** Default longest-side target. 0 disables scaling. */
	public static final int DEFAULT_MAX_IMAGE_DIM = 1288;

	private String model;

	private String prompt;

	private VlmResponseFormat responseFormat = VlmResponseFormat.TEXT;

	private int maxImageDim = DEFAULT_MAX_IMAGE_DIM;

	private int maxTokens = DEFAULT_MAX_TOKENS;

	private double temperature = 0.1;

	/**
	 * Only meaningful for {@link VlmResponseFormat#OLMOCR}: when the model reports the page was fed in sideways, rotate it and ask once more.
	 */
	private boolean retryOnRotation = true;

	public String getModel() {
		return model;
	}

	public VlmNodePrompt setModel(String model) {
		this.model = model;
		return this;
	}

	public String getPrompt() {
		return prompt;
	}

	public VlmNodePrompt setPrompt(String prompt) {
		this.prompt = prompt;
		return this;
	}

	public VlmResponseFormat getResponseFormat() {
		return responseFormat;
	}

	public VlmNodePrompt setResponseFormat(VlmResponseFormat responseFormat) {
		this.responseFormat = responseFormat;
		return this;
	}

	public int getMaxImageDim() {
		return maxImageDim;
	}

	public VlmNodePrompt setMaxImageDim(int maxImageDim) {
		this.maxImageDim = maxImageDim;
		return this;
	}

	public int getMaxTokens() {
		return maxTokens;
	}

	public VlmNodePrompt setMaxTokens(int maxTokens) {
		this.maxTokens = maxTokens;
		return this;
	}

	public double getTemperature() {
		return temperature;
	}

	public VlmNodePrompt setTemperature(double temperature) {
		this.temperature = temperature;
		return this;
	}

	public boolean isRetryOnRotation() {
		return retryOnRotation;
	}

	public VlmNodePrompt setRetryOnRotation(boolean retryOnRotation) {
		this.retryOnRotation = retryOnRotation;
		return this;
	}
}
