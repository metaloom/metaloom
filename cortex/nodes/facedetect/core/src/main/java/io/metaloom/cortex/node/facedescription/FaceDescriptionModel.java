package io.metaloom.cortex.node.facedescription;

import io.metaloom.ai.genai.llm.LargeLanguageModel;

/**
 * The vision models {@code FacedescriptionNode} is known to work with. The URL points at an
 * OpenAI-compatible server (llama.cpp with {@code --mmproj}, vLLM, Ollama's {@code /v1} endpoint);
 * the id is whatever that server advertises the model under.
 */
public enum FaceDescriptionModel implements LargeLanguageModel {

	GEMMA3_12B_IT("google/gemma-3-12b-it"),

	GEMMA3_27B_IT("google/gemma-3-27b-it");

	private String id;

	FaceDescriptionModel(String id) {
		this.id = id;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public long contextWindow() {
		return 4096;
	}

	@Override
	public String url() {
		return FacedescriptionNode.URL;
	}

}
