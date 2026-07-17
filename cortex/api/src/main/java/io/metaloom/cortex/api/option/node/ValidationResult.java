package io.metaloom.cortex.api.option.node;

import java.util.Collections;
import java.util.List;

/**
 * Result of a validation operation. Contains either a list of errors (if invalid)
 * or is empty (if valid).
 */
public class ValidationResult {

	private final List<String> errors;

	private ValidationResult(List<String> errors) {
		this.errors = errors;
	}

	/**
	 * Create a successful validation result (no errors).
	 */
	public static ValidationResult valid() {
		return new ValidationResult(Collections.emptyList());
	}

	/**
	 * Create a failed validation result with the given errors.
	 */
	public static ValidationResult invalid(List<String> errors) {
		return new ValidationResult(Collections.unmodifiableList(errors));
	}

	/**
	 * Create a failed validation result with a single error.
	 */
	public static ValidationResult invalid(String error) {
		return new ValidationResult(Collections.singletonList(error));
	}

	/**
	 * Check if validation passed (no errors).
	 */
	public boolean isValid() {
		return errors.isEmpty();
	}

	/**
	 * Check if validation failed (has errors).
	 */
	public boolean isInvalid() {
		return !errors.isEmpty();
	}

	/**
	 * Get the list of validation errors.
	 */
	public List<String> getErrors() {
		return errors;
	}

	/**
	 * Throw an exception if validation failed.
	 * 
	 * @param context context string to include in the exception message
	 * @throws IllegalStateException if validation failed
	 */
	public void throwIfInvalid(String context) {
		if (isInvalid()) {
			throw new IllegalStateException(context + ": " + String.join("; ", errors));
		}
	}

	@Override
	public String toString() {
		if (isValid()) {
			return "ValidationResult{valid}";
		}
		return "ValidationResult{invalid: " + errors + "}";
	}
}
