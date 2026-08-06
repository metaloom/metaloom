package io.metaloom.cortex.node.sam2.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.sam2.Sam2Mode;
import io.metaloom.cortex.node.sam2.Sam2NodeOptions;

/**
 * AssertJ assertions for {@link Sam2NodeOptions}.
 */
public class Sam2OptionsAssert extends AbstractCortexNodeOptionsAssert<Sam2OptionsAssert, Sam2NodeOptions> {

	public Sam2OptionsAssert(Sam2NodeOptions actual) {
		super(actual, Sam2OptionsAssert.class);
	}

	public Sam2OptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getSam2Host())) {
			failWithMessage("Expected sam2Host to be '%s' but was '%s'", expected, actual.getSam2Host());
		}
		return this;
	}

	public Sam2OptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getSam2Port() != expected) {
			failWithMessage("Expected sam2Port to be '%s' but was '%s'", expected, actual.getSam2Port());
		}
		return this;
	}

	public Sam2OptionsAssert hasMode(Sam2Mode expected) {
		isNotNull();
		if (actual.getMode() != expected) {
			failWithMessage("Expected mode to be '%s' but was '%s'", expected, actual.getMode());
		}
		return this;
	}

	public Sam2OptionsAssert hasMaxDim(int expected) {
		isNotNull();
		if (actual.getMaxDim() != expected) {
			failWithMessage("Expected maxDim to be '%s' but was '%s'", expected, actual.getMaxDim());
		}
		return this;
	}

	public Sam2OptionsAssert hasPointsPerSide(int expected) {
		isNotNull();
		if (actual.getPointsPerSide() != expected) {
			failWithMessage("Expected pointsPerSide to be '%s' but was '%s'", expected, actual.getPointsPerSide());
		}
		return this;
	}

	public Sam2OptionsAssert hasMaxMasks(int expected) {
		isNotNull();
		if (actual.getMaxMasks() != expected) {
			failWithMessage("Expected maxMasks to be '%s' but was '%s'", expected, actual.getMaxMasks());
		}
		return this;
	}

	public Sam2OptionsAssert hasMaxFrames(int expected) {
		isNotNull();
		if (actual.getMaxFrames() != expected) {
			failWithMessage("Expected maxFrames to be '%s' but was '%s'", expected, actual.getMaxFrames());
		}
		return this;
	}

	public Sam2OptionsAssert hasVideoChopRate(int expected) {
		isNotNull();
		if (actual.getVideoChopRate() != expected) {
			failWithMessage("Expected videoChopRate to be '%s' but was '%s'", expected, actual.getVideoChopRate());
		}
		return this;
	}

	public Sam2OptionsAssert emitsOverlay(boolean expected) {
		isNotNull();
		if (actual.isEmitOverlay() != expected) {
			failWithMessage("Expected emitOverlay to be '%s' but was '%s'", expected, actual.isEmitOverlay());
		}
		return this;
	}

	/**
	 * Assert the options are rejected, and that the message names the field — so a validation rule
	 * that fires for the wrong reason still fails the test.
	 */
	public Sam2OptionsAssert isInvalidBecauseOf(String fragment) {
		isNotNull();
		if (actual.validate().isValid()) {
			failWithMessage("Expected options to be invalid because of '%s' but they validated cleanly", fragment);
		}
		boolean matched = actual.validate().getErrors().stream().anyMatch(e -> e.contains(fragment));
		if (!matched) {
			failWithMessage("Expected a validation error mentioning '%s' but got: %s", fragment, actual.validate().getErrors());
		}
		return this;
	}
}
