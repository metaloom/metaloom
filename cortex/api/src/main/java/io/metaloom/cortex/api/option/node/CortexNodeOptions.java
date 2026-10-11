package io.metaloom.cortex.api.option.node;

public interface CortexNodeOptions {

	boolean isEnabled();

	void setEnabled(boolean flag);

	long getTimeoutMs();

	void setTimeoutMs(long timeoutMs);

	/**
	 * Whether the node must compute even when Loom already holds its output for this asset, options and inputs.
	 *
	 * @return false unless the options say otherwise
	 */
	default boolean isRecompute() {
		return false;
	}

	/**
	 * Validate the options and return a validation result.
	 * 
	 * @return ValidationResult containing any errors, or valid if no errors
	 */
	default ValidationResult validate() {
		return ValidationResult.valid();
	}
}
