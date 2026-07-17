package io.metaloom.cortex.node.captioning.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link CaptioningNodeOptions}.
 */
public class CaptioningNodeOptionsAssert extends CortexNodeOptionsAssert {

	public CaptioningNodeOptionsAssert(CaptioningNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as CaptioningNodeOptions.
	 */
	private CaptioningNodeOptions captionOptions() {
		return (CaptioningNodeOptions) actual;
	}

	/**
	 * Assert that the smolVLMHost is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasSmolVLMHost(String expectedHost) {
		isNotNull();
		if (!expectedHost.equals(captionOptions().getSmolVLMHost())) {
			failWithMessage("Expected smolVLMHost to be '%s' but was '%s'", expectedHost, captionOptions().getSmolVLMHost());
		}
		return this;
	}

	/**
	 * Assert that the smolVLMPort is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasSmolVLMPort(int expectedPort) {
		isNotNull();
		if (captionOptions().getSmolVLMPort() != expectedPort) {
			failWithMessage("Expected smolVLMPort to be %d but was %d", expectedPort, captionOptions().getSmolVLMPort());
		}
		return this;
	}
}