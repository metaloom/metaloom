package io.metaloom.cortex.node.scenelayout.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeOptions;

/**
 * AssertJ assertions for {@link SceneLayoutNodeOptions}.
 */
public class SceneLayoutOptionsAssert extends AbstractCortexNodeOptionsAssert<SceneLayoutOptionsAssert, SceneLayoutNodeOptions> {

	public SceneLayoutOptionsAssert(SceneLayoutNodeOptions actual) {
		super(actual, SceneLayoutOptionsAssert.class);
	}

	public SceneLayoutOptionsAssert hasCoreInset(double expected) {
		isNotNull();
		if (actual.getCoreInset() != expected) {
			failWithMessage("Expected coreInset to be '%s' but was '%s'", expected, actual.getCoreInset());
		}
		return this;
	}

	public SceneLayoutOptionsAssert hasDepthZThreshold(double expected) {
		isNotNull();
		if (actual.getDepthZThreshold() != expected) {
			failWithMessage("Expected depthZThreshold to be '%s' but was '%s'", expected, actual.getDepthZThreshold());
		}
		return this;
	}

	public SceneLayoutOptionsAssert isValid() {
		isNotNull();
		if (!actual.validate().isValid()) {
			failWithMessage("Expected options to be valid but got errors: %s", actual.validate().getErrors());
		}
		return this;
	}

	public SceneLayoutOptionsAssert isInvalidBecauseOf(String fragment) {
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
