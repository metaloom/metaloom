package io.metaloom.cortex.node.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.llm.AbstractLlmNodeOptions;

public class LLMNodeOptions extends AbstractLlmNodeOptions<LLMNodeOptions> {

	public static final String KEY = "llm";

	private Map<String, LLMNodePrompt> prompts = new HashMap<>();

	public Map<String, LLMNodePrompt> getPrompts() {
		return prompts;
	}

	public void setPrompts(Map<String, LLMNodePrompt> prompts) {
		this.prompts = prompts;
	}

	@Override
	protected LLMNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		// openaiUrl must not be empty; contextWindow must be positive
		errors.addAll(validateEndpoint());

		// prompts must not be empty
		if (prompts == null || prompts.isEmpty()) {
			errors.add("prompts must not be empty");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
