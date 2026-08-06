package io.metaloom.cortex.node.guard.assertj;

import java.util.List;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.guard.GuardFamily;
import io.metaloom.cortex.node.guard.GuardNodeOptions;

/**
 * AssertJ assertions for {@link GuardNodeOptions}.
 */
public class GuardOptionsAssert extends AbstractCortexNodeOptionsAssert<GuardOptionsAssert, GuardNodeOptions> {

	public GuardOptionsAssert(GuardNodeOptions actual) {
		super(actual, GuardOptionsAssert.class);
	}

	public GuardOptionsAssert hasFamily(GuardFamily expected) {
		isNotNull();
		if (expected != actual.getFamily()) {
			failWithMessage("Expected family to be '%s' but was '%s'", expected, actual.getFamily());
		}
		return this;
	}

	public GuardOptionsAssert hasModel(String expected) {
		isNotNull();
		if (!expected.equals(actual.getModel())) {
			failWithMessage("Expected model to be '%s' but was '%s'", expected, actual.getModel());
		}
		return this;
	}

	public GuardOptionsAssert hasOpenaiUrl(String expected) {
		isNotNull();
		if (!expected.equals(actual.openaiUrl())) {
			failWithMessage("Expected openaiUrl to be '%s' but was '%s'", expected, actual.openaiUrl());
		}
		return this;
	}

	public GuardOptionsAssert hasThreshold(double expected) {
		isNotNull();
		if (Double.compare(expected, actual.getThreshold()) != 0) {
			failWithMessage("Expected threshold to be %s but was %s", expected, actual.getThreshold());
		}
		return this;
	}

	public GuardOptionsAssert hasMaxChars(int expected) {
		isNotNull();
		if (actual.getMaxChars() != expected) {
			failWithMessage("Expected maxChars to be %d but was %d", expected, actual.getMaxChars());
		}
		return this;
	}

	public GuardOptionsAssert hasMaxImageDim(int expected) {
		isNotNull();
		if (actual.getMaxImageDim() != expected) {
			failWithMessage("Expected maxImageDim to be %d but was %d", expected, actual.getMaxImageDim());
		}
		return this;
	}

	/** The codes that will actually be probed — the configured selection, or the whole family. */
	public GuardOptionsAssert hasEffectiveCategories(List<String> expected) {
		isNotNull();
		if (!expected.equals(actual.effectiveCategories())) {
			failWithMessage("Expected effective categories to be %s but were %s", expected, actual.effectiveCategories());
		}
		return this;
	}
}
