package io.metaloom.cortex.node.captioning;

/**
 * How the captioning node turns a <b>video</b> into a caption. Images always use the {@link SmolVLMClient} still-frame path; only video is affected by this
 * choice. Each strategy drives the same OpenAI-compatible {@link VideoVLMClient}, so the backend (vLLM / llama.cpp) is selected purely by options.
 */
public enum VideoCaptioningStrategy {

	/**
	 * Sample {@code frameCount} frames evenly across the whole clip → one multi-image prompt → a single caption. Works on every OpenAI-compatible backend
	 * and on clips of any length (fixed frame budget). The recommended default.
	 */
	WHOLE,

	/**
	 * Segment the clip into scenes up front (optical-flow scene detector) → caption each scene independently → a per-scene caption timeline. Produces the
	 * richer {@code scenes} breakdown. Works on any OpenAI-compatible backend.
	 */
	SCENE,

	/**
	 * Hand the whole file to the server via a {@code video_url} part and let it do its own temporal sampling. Highest quality when the clip fits the model
	 * context window, but vLLM-only (llama.cpp does not decode video) and fails on longer clips at small context sizes.
	 */
	NATIVE;
}
