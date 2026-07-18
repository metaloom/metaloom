package io.metaloom.cortex.api.option.assertj;

import java.util.function.Predicate;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Base assertions shared by every node options asserter.
 *
 * <p>The {@code SELF} type parameter is what makes assertions chainable across
 * the inheritance boundary: {@link #isValid()} returns {@code SELF}, so
 * {@code assertThat(hashOptions).isValid().hasMD5(true)} keeps the node-specific
 * type instead of widening to the base asserter. Subclasses must bind
 * {@code SELF} to themselves:</p>
 *
 * <pre>
 * public class HashNodeOptionsAssert
 *         extends AbstractCortexNodeOptionsAssert&lt;HashNodeOptionsAssert, HashNodeOptions&gt; {
 * </pre>
 *
 * <p>{@code ACTUAL} is bound to the concrete options type, so subclasses can use
 * the inherited {@code actual} field directly without casting.</p>
 *
 * @param <SELF>   the concrete asserter type, for fluent chaining
 * @param <ACTUAL> the concrete options type under assertion
 */
public abstract class AbstractCortexNodeOptionsAssert<SELF extends AbstractCortexNodeOptionsAssert<SELF, ACTUAL>, ACTUAL extends CortexNodeOptions>
		extends AbstractAssert<SELF, ACTUAL> {

	protected AbstractCortexNodeOptionsAssert(ACTUAL actual, Class<?> selfType) {
		super(actual, selfType);
	}

	/**
	 * Assert that the options are valid (validation passes).
	 */
	public SELF isValid() {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isInvalid()) {
			failWithMessage("Expected options to be valid but got errors: %s", result.getErrors());
		}
		return myself;
	}

	/**
	 * Assert that the options are invalid (validation fails).
	 */
	public SELF isInvalid() {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to be invalid but validation passed");
		}
		return myself;
	}

	/**
	 * Assert that the validation result contains a specific error message.
	 */
	public SELF hasError(String expectedError) {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to have error '%s' but validation passed", expectedError);
		}
		if (result.getErrors().stream().noneMatch(e -> e.contains(expectedError))) {
			failWithMessage("Expected options to have error containing '%s' but got: %s", expectedError, result.getErrors());
		}
		return myself;
	}

	/**
	 * Assert that the validation result contains an error matching the given predicate.
	 */
	public SELF hasErrorMatching(Predicate<String> predicate) {
		isNotNull();
		ValidationResult result = actual.validate();
		if (result.isValid()) {
			failWithMessage("Expected options to have error matching predicate but validation passed");
		}
		if (result.getErrors().stream().noneMatch(predicate)) {
			failWithMessage("Expected options to have error matching predicate but got: %s", result.getErrors());
		}
		return myself;
	}

	/**
	 * Assert that the validation result has exactly the given number of errors.
	 */
	public SELF hasErrorCount(int expectedCount) {
		isNotNull();
		ValidationResult result = actual.validate();
		int actualCount = result.getErrors().size();
		if (actualCount != expectedCount) {
			failWithMessage("Expected %d errors but got %d: %s", expectedCount, actualCount, result.getErrors());
		}
		return myself;
	}

	/**
	 * Get the validation result for further assertions.
	 */
	public ValidationResultAssert validationResult() {
		isNotNull();
		return new ValidationResultAssert(actual.validate());
	}
}
