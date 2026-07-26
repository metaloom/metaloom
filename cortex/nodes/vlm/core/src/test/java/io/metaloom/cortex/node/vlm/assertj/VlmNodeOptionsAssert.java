package io.metaloom.cortex.node.vlm.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.vlm.VlmNodeOptions;
import io.metaloom.cortex.node.vlm.VlmNodePrompt;

/**
 * AssertJ assertions for {@link VlmNodeOptions}.
 */
public class VlmNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<VlmNodeOptionsAssert, VlmNodeOptions> {

	public VlmNodeOptionsAssert(VlmNodeOptions actual) {
		super(actual, VlmNodeOptionsAssert.class);
	}

	/**
	 * Assert that the endpoint URL is set to the expected value.
	 */
	public VlmNodeOptionsAssert hasEndpointUrl(String expectedUrl) {
		isNotNull();
		if (!expectedUrl.equals(actual.getEndpointUrl())) {
			failWithMessage("Expected endpointUrl to be '%s' but was '%s'", expectedUrl, actual.getEndpointUrl());
		}
		return this;
	}

	/**
	 * Assert that a prompt is registered under the given id.
	 */
	public VlmNodeOptionsAssert hasPrompt(String id) {
		isNotNull();
		if (!actual.getPrompts().containsKey(id)) {
			failWithMessage("Expected a prompt with id '%s' but found %s", id, actual.getPrompts().keySet());
		}
		return this;
	}

	/**
	 * Assert that the prompt with the given id uses the expected model.
	 */
	public VlmNodeOptionsAssert hasPromptModel(String id, String expectedModel) {
		hasPrompt(id);
		VlmNodePrompt prompt = actual.getPrompts().get(id);
		if (!expectedModel.equals(prompt.getModel())) {
			failWithMessage("Expected prompt '%s' to use model '%s' but was '%s'", id, expectedModel, prompt.getModel());
		}
		return this;
	}

	/**
	 * Assert on the number of configured prompts.
	 */
	public VlmNodeOptionsAssert hasPromptCount(int expected) {
		isNotNull();
		if (actual.getPrompts().size() != expected) {
			failWithMessage("Expected %s prompts but found %s: %s", expected, actual.getPrompts().size(), actual.getPrompts().keySet());
		}
		return this;
	}
}
