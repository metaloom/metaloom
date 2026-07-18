package io.metaloom.cortex.node.captioning.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;

/**
 * AssertJ assertions for {@link CaptioningNodeOptions}.
 */
public class CaptioningNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<CaptioningNodeOptionsAssert, CaptioningNodeOptions> {

	public CaptioningNodeOptionsAssert(CaptioningNodeOptions actual) {
		super(actual, CaptioningNodeOptionsAssert.class);
	}

	/**
	 * Assert that the smolVLMHost is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasSmolVLMHost(String expectedHost) {
		isNotNull();
		if (!expectedHost.equals(actual.getSmolVLMHost())) {
			failWithMessage("Expected smolVLMHost to be '%s' but was '%s'", expectedHost, actual.getSmolVLMHost());
		}
		return this;
	}

	/**
	 * Assert that the smolVLMPort is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasSmolVLMPort(int expectedPort) {
		isNotNull();
		if (actual.getSmolVLMPort() != expectedPort) {
			failWithMessage("Expected smolVLMPort to be %d but was %d", expectedPort, actual.getSmolVLMPort());
		}
		return this;
	}
}
