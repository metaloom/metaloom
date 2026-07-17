package io.metaloom.cortex.api.option.node;

public interface CortexNodeOptions {

	boolean isEnabled();

	void setEnabled(boolean flag);

	long getTimeoutMs();

	void setTimeoutMs(long timeoutMs);

	/**
	 * Validate the options and return a validation result.
	 * 
	 * @return ValidationResult containing any errors, or valid if no errors
	 */
	default ValidationResult validate() {
		return ValidationResult.valid();
	}
}
