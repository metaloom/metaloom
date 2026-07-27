package io.metaloom.cortex.node.imagegen.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.imagegen.ImageGenMode;
import io.metaloom.cortex.node.imagegen.ImageGenNodeOptions;

/**
 * AssertJ assertions for {@link ImageGenNodeOptions}.
 */
public class ImageGenOptionsAssert extends AbstractCortexNodeOptionsAssert<ImageGenOptionsAssert, ImageGenNodeOptions> {

	public ImageGenOptionsAssert(ImageGenNodeOptions actual) {
		super(actual, ImageGenOptionsAssert.class);
	}

	public ImageGenOptionsAssert hasMode(ImageGenMode expected) {
		isNotNull();
		if (actual.getMode() != expected) {
			failWithMessage("Expected mode to be '%s' but was '%s'", expected, actual.getMode());
		}
		return this;
	}

	public ImageGenOptionsAssert hasPrompt(String expected) {
		isNotNull();
		if (!expected.equals(actual.getPrompt())) {
			failWithMessage("Expected prompt to be '%s' but was '%s'", expected, actual.getPrompt());
		}
		return this;
	}

	public ImageGenOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getHost())) {
			failWithMessage("Expected host to be '%s' but was '%s'", expected, actual.getHost());
		}
		return this;
	}

	public ImageGenOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getPort() != expected) {
			failWithMessage("Expected port to be %d but was %d", expected, actual.getPort());
		}
		return this;
	}

	public ImageGenOptionsAssert hasWidth(int expected) {
		isNotNull();
		if (actual.getWidth() != expected) {
			failWithMessage("Expected width to be %d but was %d", expected, actual.getWidth());
		}
		return this;
	}

	public ImageGenOptionsAssert hasHeight(int expected) {
		isNotNull();
		if (actual.getHeight() != expected) {
			failWithMessage("Expected height to be %d but was %d", expected, actual.getHeight());
		}
		return this;
	}

	public ImageGenOptionsAssert hasSteps(int expected) {
		isNotNull();
		if (actual.getSteps() != expected) {
			failWithMessage("Expected steps to be %d but was %d", expected, actual.getSteps());
		}
		return this;
	}

	public ImageGenOptionsAssert hasStrength(double expected) {
		isNotNull();
		if (actual.getStrength() != expected) {
			failWithMessage("Expected strength to be %s but was %s", expected, actual.getStrength());
		}
		return this;
	}
}
