package io.metaloom.cortex.node.videocaptioning;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options shared by every video-captioning variant. The backend is selected purely by {@link #endpointUrl} + {@link #model}, so the same node runs against
 * vLLM or llama.cpp by config alone.
 */
public class VideoCaptioningNodeOptions extends AbstractNodeOptions<VideoCaptioningNodeOptions> {

	private String endpointUrl = "http://localhost:8000";
	private String model = "qwen25vl-awq";
	private String apiKey = "";

	/** Frames sampled across the whole video (whole-video variant) or per scene (scene variant). */
	private int frameCount = 8;
	/** Longest-edge pixel size each sampled frame is scaled to before base64 encoding. */
	private int targetFrameSize = 512;
	/** Upper bound on scenes captioned by the scene variant (protects against pathological over-segmentation). */
	private int maxScenes = 32;
	private int maxTokens = 256;
	private double temperature = 0.2d;
	private String prompt = "Describe what happens in this video in two or three sentences. Focus on actions, subjects and setting.";

	@Override
	protected VideoCaptioningNodeOptions self() {
		return this;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public VideoCaptioningNodeOptions setEndpointUrl(String endpointUrl) {
		this.endpointUrl = endpointUrl;
		return this;
	}

	public String getModel() {
		return model;
	}

	public VideoCaptioningNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public String getApiKey() {
		return apiKey;
	}

	public VideoCaptioningNodeOptions setApiKey(String apiKey) {
		this.apiKey = apiKey;
		return this;
	}

	public int getFrameCount() {
		return frameCount;
	}

	public VideoCaptioningNodeOptions setFrameCount(int frameCount) {
		this.frameCount = frameCount;
		return this;
	}

	public int getTargetFrameSize() {
		return targetFrameSize;
	}

	public VideoCaptioningNodeOptions setTargetFrameSize(int targetFrameSize) {
		this.targetFrameSize = targetFrameSize;
		return this;
	}

	public int getMaxScenes() {
		return maxScenes;
	}

	public VideoCaptioningNodeOptions setMaxScenes(int maxScenes) {
		this.maxScenes = maxScenes;
		return this;
	}

	public int getMaxTokens() {
		return maxTokens;
	}

	public VideoCaptioningNodeOptions setMaxTokens(int maxTokens) {
		this.maxTokens = maxTokens;
		return this;
	}

	public double getTemperature() {
		return temperature;
	}

	public VideoCaptioningNodeOptions setTemperature(double temperature) {
		this.temperature = temperature;
		return this;
	}

	public String getPrompt() {
		return prompt;
	}

	public VideoCaptioningNodeOptions setPrompt(String prompt) {
		this.prompt = prompt;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		if (endpointUrl == null || endpointUrl.isBlank()) {
			errors.add("endpointUrl must not be empty");
		}
		if (model == null || model.isBlank()) {
			errors.add("model must not be empty");
		}
		if (frameCount <= 0) {
			errors.add("frameCount must be positive, got " + frameCount);
		}
		if (targetFrameSize <= 0) {
			errors.add("targetFrameSize must be positive, got " + targetFrameSize);
		}
		if (maxTokens <= 0) {
			errors.add("maxTokens must be positive, got " + maxTokens);
		}
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
