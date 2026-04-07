package io.metaloom.cortex.node.llm;

public class LLMNodePrompt {

	private String model;

	private String prompt;

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

}
