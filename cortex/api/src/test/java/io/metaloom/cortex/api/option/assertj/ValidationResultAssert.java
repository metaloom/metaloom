package io.metaloom.cortex.api.option.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * AssertJ assertions for {@link ValidationResult}.
 */
public class ValidationResultAssert extends AbstractAssert<ValidationResultAssert, ValidationResult> {

	public ValidationResultAssert(ValidationResult actual) {
		super(actual, ValidationResultAssert.class);
	}

	/**
	 * Assert that the validation result is valid (no errors).
	 */
	public ValidationResultAssert isValid() {
		isNotNull();
		if (actual.isInvalid()) {
			failWithMessage("Expected validation to be valid but got errors: %s", actual.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result is invalid (has errors).
	 */
	public ValidationResultAssert isInvalid() {
		isNotNull();
		if (actual.isValid()) {
			failWithMessage("Expected validation to be invalid but it passed");
		}
		return this;
	}

	/**
	 * Assert that the validation result contains a specific error message.
	 */
	public ValidationResultAssert hasError(String expectedError) {
		isNotNull();
		if (actual.isValid()) {
			failWithMessage("Expected validation to have error '%s' but validation passed", expectedError);
		}
		if (actual.getErrors().stream().noneMatch(e -> e.contains(expectedError))) {
			failWithMessage("Expected validation to have error containing '%s' but got: %s", expectedError, actual.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result contains an error matching the given predicate.
	 */
	public ValidationResultAssert hasErrorMatching(java.util.function.Predicate<String> predicate) {
		isNotNull();
		if (actual.isValid()) {
			failWithMessage("Expected validation to have error matching predicate but validation passed");
		}
		if (actual.getErrors().stream().noneMatch(predicate)) {
			failWithMessage("Expected validation to have error matching predicate but got: %s", actual.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result has exactly the given number of errors.
	 */
	public ValidationResultAssert hasErrorCount(int expectedCount) {
		isNotNull();
		int actualCount = actual.getErrors().size();
		if (actualCount != expectedCount) {
			failWithMessage("Expected %d errors but got %d: %s", expectedCount, actualCount, actual.getErrors());
		}
		return this;
	}

	/**
	 * Assert that the validation result has no errors.
	 */
	public ValidationResultAssert hasNoErrors() {
		isNotNull();
		if (actual.isInvalid()) {
			failWithMessage("Expected no errors but got: %s", actual.getErrors());
		}
		return this;
	}
}