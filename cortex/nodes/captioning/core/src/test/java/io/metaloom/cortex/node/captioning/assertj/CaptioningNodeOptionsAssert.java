package io.metaloom.cortex.node.captioning.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;
import io.metaloom.cortex.node.captioning.VideoCaptioningStrategy;

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

	/**
	 * Assert that the videoStrategy is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasVideoStrategy(VideoCaptioningStrategy expected) {
		isNotNull();
		if (actual.getVideoStrategy() != expected) {
			failWithMessage("Expected videoStrategy to be %s but was %s", expected, actual.getVideoStrategy());
		}
		return this;
	}

	/**
	 * Assert that the videoEndpointUrl is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasVideoEndpointUrl(String expected) {
		isNotNull();
		if (!expected.equals(actual.getVideoEndpointUrl())) {
			failWithMessage("Expected videoEndpointUrl to be '%s' but was '%s'", expected, actual.getVideoEndpointUrl());
		}
		return this;
	}

	/**
	 * Assert that the videoModel is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasVideoModel(String expected) {
		isNotNull();
		if (!expected.equals(actual.getVideoModel())) {
			failWithMessage("Expected videoModel to be '%s' but was '%s'", expected, actual.getVideoModel());
		}
		return this;
	}

	/**
	 * Assert that the frameCount is set to the expected value.
	 */
	public CaptioningNodeOptionsAssert hasFrameCount(int expected) {
		isNotNull();
		if (actual.getFrameCount() != expected) {
			failWithMessage("Expected frameCount to be %d but was %d", expected, actual.getFrameCount());
		}
		return this;
	}
}
