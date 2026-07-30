package io.metaloom.cortex.node.videogen.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.videogen.VideoGenMode;
import io.metaloom.cortex.node.videogen.VideoGenNodeOptions;

/**
 * AssertJ assertions for {@link VideoGenNodeOptions}.
 */
public class VideoGenOptionsAssert extends AbstractCortexNodeOptionsAssert<VideoGenOptionsAssert, VideoGenNodeOptions> {

	public VideoGenOptionsAssert(VideoGenNodeOptions actual) {
		super(actual, VideoGenOptionsAssert.class);
	}

	public VideoGenOptionsAssert hasMode(VideoGenMode expected) {
		isNotNull();
		if (actual.getMode() != expected) {
			failWithMessage("Expected mode to be '%s' but was '%s'", expected, actual.getMode());
		}
		return this;
	}

	public VideoGenOptionsAssert hasPrompt(String expected) {
		isNotNull();
		if (!expected.equals(actual.getPrompt())) {
			failWithMessage("Expected prompt to be '%s' but was '%s'", expected, actual.getPrompt());
		}
		return this;
	}

	public VideoGenOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getHost())) {
			failWithMessage("Expected host to be '%s' but was '%s'", expected, actual.getHost());
		}
		return this;
	}

	public VideoGenOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getPort() != expected) {
			failWithMessage("Expected port to be %d but was %d", expected, actual.getPort());
		}
		return this;
	}

	public VideoGenOptionsAssert hasWidth(int expected) {
		isNotNull();
		if (actual.getWidth() != expected) {
			failWithMessage("Expected width to be %d but was %d", expected, actual.getWidth());
		}
		return this;
	}

	public VideoGenOptionsAssert hasHeight(int expected) {
		isNotNull();
		if (actual.getHeight() != expected) {
			failWithMessage("Expected height to be %d but was %d", expected, actual.getHeight());
		}
		return this;
	}

	public VideoGenOptionsAssert hasNumFrames(int expected) {
		isNotNull();
		if (actual.getNumFrames() != expected) {
			failWithMessage("Expected numFrames to be %d but was %d", expected, actual.getNumFrames());
		}
		return this;
	}

	public VideoGenOptionsAssert hasFps(int expected) {
		isNotNull();
		if (actual.getFps() != expected) {
			failWithMessage("Expected fps to be %d but was %d", expected, actual.getFps());
		}
		return this;
	}

	public VideoGenOptionsAssert hasSteps(int expected) {
		isNotNull();
		if (actual.getSteps() != expected) {
			failWithMessage("Expected steps to be %d but was %d", expected, actual.getSteps());
		}
		return this;
	}

	public VideoGenOptionsAssert hasGuidance(double expected) {
		isNotNull();
		if (actual.getGuidance() != expected) {
			failWithMessage("Expected guidance to be %s but was %s", expected, actual.getGuidance());
		}
		return this;
	}
}
