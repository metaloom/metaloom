package io.metaloom.cortex.node.imagemanip.assertj;

import java.util.List;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNodeOptions;
import io.metaloom.cortex.node.imagemanip.Op;

/**
 * AssertJ assertions for {@link ImageManipulationNodeOptions}.
 */
public class ImageManipulationOptionsAssert
	extends AbstractCortexNodeOptionsAssert<ImageManipulationOptionsAssert, ImageManipulationNodeOptions> {

	public ImageManipulationOptionsAssert(ImageManipulationNodeOptions actual) {
		super(actual, ImageManipulationOptionsAssert.class);
	}

	public ImageManipulationOptionsAssert hasOperations(Op... expected) {
		isNotNull();
		List<Op> chain = actual.operationChain();
		if (!chain.equals(List.of(expected))) {
			failWithMessage("Expected the operation chain to be %s but was %s", List.of(expected), chain);
		}
		return this;
	}

	public ImageManipulationOptionsAssert hasTargetAspect(String expected) {
		isNotNull();
		if (!java.util.Objects.equals(actual.getTargetAspect(), expected)) {
			failWithMessage("Expected targetAspect to be %s but was %s", expected, actual.getTargetAspect());
		}
		return this;
	}

	public ImageManipulationOptionsAssert hasMaxLongEdge(int expected) {
		isNotNull();
		if (actual.getMaxLongEdge() != expected) {
			failWithMessage("Expected maxLongEdge to be %s but was %s", expected, actual.getMaxLongEdge());
		}
		return this;
	}

	/** The options validate cleanly. */
	public ImageManipulationOptionsAssert isValid() {
		isNotNull();
		ValidationResult result = actual.validate();
		if (!result.isValid()) {
			failWithMessage("Expected the options to be valid but got %s", result.getErrors());
		}
		return this;
	}

	/**
	 * Validation fails, and at least one message mentions {@code fragment}.
	 *
	 * <p>
	 * Matching on a fragment rather than the whole message keeps the tests readable while still pinning
	 * <em>which</em> rule fired - asserting only "invalid" would pass for any mistake at all.
	 * </p>
	 */
	public ImageManipulationOptionsAssert isInvalidBecauseOf(String fragment) {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected the options to be invalid because of '%s' but they validated cleanly", fragment);
		}
		if (result.getErrors().stream().noneMatch(error -> error.contains(fragment))) {
			failWithMessage("Expected a validation error mentioning '%s' but got %s", fragment, result.getErrors());
		}
		return this;
	}
}
