package io.metaloom.cortex.node.depthmap.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.depthmap.DepthMode;
import io.metaloom.cortex.node.depthmap.DepthmapNodeOptions;

/**
 * AssertJ assertions for {@link DepthmapNodeOptions}.
 */
public class DepthmapOptionsAssert extends AbstractCortexNodeOptionsAssert<DepthmapOptionsAssert, DepthmapNodeOptions> {

	public DepthmapOptionsAssert(DepthmapNodeOptions actual) {
		super(actual, DepthmapOptionsAssert.class);
	}

	public DepthmapOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getDepthHost())) {
			failWithMessage("Expected depthHost to be '%s' but was '%s'", expected, actual.getDepthHost());
		}
		return this;
	}

	public DepthmapOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getDepthPort() != expected) {
			failWithMessage("Expected depthPort to be '%s' but was '%s'", expected, actual.getDepthPort());
		}
		return this;
	}

	public DepthmapOptionsAssert hasMode(DepthMode expected) {
		isNotNull();
		if (actual.getMode() != expected) {
			failWithMessage("Expected mode to be '%s' but was '%s'", expected, actual.getMode());
		}
		return this;
	}

	public DepthmapOptionsAssert hasMaxDim(int expected) {
		isNotNull();
		if (actual.getMaxDim() != expected) {
			failWithMessage("Expected maxDim to be '%s' but was '%s'", expected, actual.getMaxDim());
		}
		return this;
	}

	public DepthmapOptionsAssert isValid() {
		isNotNull();
		if (!actual.validate().isValid()) {
			failWithMessage("Expected options to be valid but got errors: %s", actual.validate().getErrors());
		}
		return this;
	}

	public DepthmapOptionsAssert isInvalidBecauseOf(String fragment) {
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
