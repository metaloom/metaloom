package io.metaloom.cortex.node.captioning;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the captioning node. Images are captioned with the {@link SmolVLMClient} still-frame path (host/port below). Video is captioned with the
 * OpenAI-compatible {@link VideoVLMClient} (Qwen2.5-VL on vLLM / llama.cpp); the {@link #videoStrategy} selects how a clip is turned into a caption and the
 * backend is selected purely by {@link #videoEndpointUrl} + {@link #videoModel}.
 */
public class CaptioningNodeOptions extends AbstractNodeOptions<CaptioningNodeOptions> {

	// -- Image captioning (SmolVLM still-frame path) ------------------------
	private int smolVLMPort = 8000;
	private String smolVLMHost = "localhost";

	// -- Video captioning (OpenAI-compatible VLM) ---------------------------
	/** Which strategy turns a video into a caption (whole-video / per-scene / native video_url). */
	private VideoCaptioningStrategy videoStrategy = VideoCaptioningStrategy.WHOLE;
	private String videoEndpointUrl = "http://localhost:8000";
	private String videoModel = "qwen25vl-awq";
	private String videoApiKey = "";
	/** Frames sampled across the whole video (whole strategy) or per scene (scene strategy). */
	private int frameCount = 8;
	/** Longest-edge pixel size each sampled frame is scaled to before base64 encoding. */
	private int targetFrameSize = 512;
	/** Upper bound on scenes captioned by the scene strategy (protects against pathological over-segmentation). */
	private int maxScenes = 32;
	private int maxTokens = 256;
	private double temperature = 0.2d;
	private String videoPrompt = "Describe what happens in this video in two or three sentences. Focus on actions, subjects and setting.";

	@Override
	protected CaptioningNodeOptions self() {
		return this;
	}

	public int getSmolVLMPort() {
		return smolVLMPort;
	}

	public CaptioningNodeOptions setSmolVLMPort(int smolVLMPort) {
		this.smolVLMPort = smolVLMPort;
		return this;
	}

	public String getSmolVLMHost() {
		return smolVLMHost;
	}

	public CaptioningNodeOptions setSmolVLMHost(String smolVLMHost) {
		this.smolVLMHost = smolVLMHost;
		return this;
	}

	public VideoCaptioningStrategy getVideoStrategy() {
		return videoStrategy;
	}

	public CaptioningNodeOptions setVideoStrategy(VideoCaptioningStrategy videoStrategy) {
		this.videoStrategy = videoStrategy;
		return this;
	}

	public String getVideoEndpointUrl() {
		return videoEndpointUrl;
	}

	public CaptioningNodeOptions setVideoEndpointUrl(String videoEndpointUrl) {
		this.videoEndpointUrl = videoEndpointUrl;
		return this;
	}

	public String getVideoModel() {
		return videoModel;
	}

	public CaptioningNodeOptions setVideoModel(String videoModel) {
		this.videoModel = videoModel;
		return this;
	}

	public String getVideoApiKey() {
		return videoApiKey;
	}

	public CaptioningNodeOptions setVideoApiKey(String videoApiKey) {
		this.videoApiKey = videoApiKey;
		return this;
	}

	public int getFrameCount() {
		return frameCount;
	}

	public CaptioningNodeOptions setFrameCount(int frameCount) {
		this.frameCount = frameCount;
		return this;
	}

	public int getTargetFrameSize() {
		return targetFrameSize;
	}

	public CaptioningNodeOptions setTargetFrameSize(int targetFrameSize) {
		this.targetFrameSize = targetFrameSize;
		return this;
	}

	public int getMaxScenes() {
		return maxScenes;
	}

	public CaptioningNodeOptions setMaxScenes(int maxScenes) {
		this.maxScenes = maxScenes;
		return this;
	}

	public int getMaxTokens() {
		return maxTokens;
	}

	public CaptioningNodeOptions setMaxTokens(int maxTokens) {
		this.maxTokens = maxTokens;
		return this;
	}

	public double getTemperature() {
		return temperature;
	}

	public CaptioningNodeOptions setTemperature(double temperature) {
		this.temperature = temperature;
		return this;
	}

	public String getVideoPrompt() {
		return videoPrompt;
	}

	public CaptioningNodeOptions setVideoPrompt(String videoPrompt) {
		this.videoPrompt = videoPrompt;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		// Image (SmolVLM) endpoint
		if (smolVLMHost == null || smolVLMHost.isBlank()) {
			errors.add("smolVLMHost must not be empty");
		}
		if (smolVLMPort <= 0) {
			errors.add("smolVLMPort must be positive, got " + smolVLMPort);
		}

		// Video (OpenAI-compatible VLM) endpoint
		if (videoStrategy == null) {
			errors.add("videoStrategy must not be null");
		}
		if (videoEndpointUrl == null || videoEndpointUrl.isBlank()) {
			errors.add("videoEndpointUrl must not be empty");
		}
		if (videoModel == null || videoModel.isBlank()) {
			errors.add("videoModel must not be empty");
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
