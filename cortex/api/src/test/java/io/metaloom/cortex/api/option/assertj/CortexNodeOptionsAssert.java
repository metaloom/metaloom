package io.metaloom.cortex.api.option.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * AssertJ assertions for {@link CortexNodeOptions}.
 */
public class CortexNodeOptionsAssert extends AbstractAssert<CortexNodeOptionsAssert, CortexNodeOptions> {

	public CortexNodeOptionsAssert(CortexNodeOptions actual) {
		super(actual, CortexNodeOptionsAssert.class);
	}

	/**
	 * Assert that the options are valid (validation passes).
	 */
	public CortexNodeOptionsAssert isValid() {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isInvalid()) {
			failWithMessage("Expected options to be valid but got errors: %s", result.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the options are invalid (validation fails).
	 */
	public CortexNodeOptionsAssert isInvalid() {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to be invalid but validation passed");
		}
		return this;
	}

	/**
	 * Assert that the validation result contains a specific error message.
	 */
	public CortexNodeOptionsAssert hasError(String expectedError) {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to have error '%s' but validation passed", expectedError);
		}
		if (result.getErrors().stream().noneMatch(e -> e.contains(expectedError))) {
			failWithMessage("Expected options to have error containing '%s' but got: %s", expectedError, result.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result contains an error matching the given predicate.
	 */
	public CortexNodeOptionsAssert hasErrorMatching(java.util.function.Predicate<String> predicate) {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to have error matching predicate but validation passed");
		}
		if (result.getErrors().stream().noneMatch(predicate)) {
			failWithMessage("Expected options to have error matching predicate but got: %s", result.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result has exactly the given number of errors.
	 */
	public CortexNodeOptionsAssert hasErrorCount(int expectedCount) {
		isNotNull();
		ValidationResult result = actual.validate();
		int actualCount = result.getErrors().size();
		if (actualCount != expectedCount) {
			failWithMessage("Expected %d errors but got %d: %s", expectedCount, actualCount, result.getErrors());
		}
		return this;
	}

	/**
	 * Get the validation result for further assertions.
	 */
	public ValidationResultAssert validationResult() {
		isNotNull();
		return new ValidationResultAssert(actual.validate());
	}
}