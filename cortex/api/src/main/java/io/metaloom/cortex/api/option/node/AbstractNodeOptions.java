package io.metaloom.cortex.api.option.node;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractNodeOptions<T extends AbstractNodeOptions<T>> implements CortexNodeOptions {

	private boolean enabled = true;

	private boolean processIncomplete;

	private boolean retryFailed;

	private long timeoutMs;

	protected abstract T self();

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean flag) {
		this.enabled = flag;
	}

	public boolean isProcessIncomplete() {
		return processIncomplete;
	}

	public T setProcessIncomplete(boolean processIncomplete) {
		this.processIncomplete = processIncomplete;
		return self();
	}

	public boolean isRetryFailed() {
		return retryFailed;
	}

	public T setRetryFailed(boolean retryFailed) {
		this.retryFailed = retryFailed;
		return self();
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

	@Override
	public void setTimeoutMs(long timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Validate the common options (enabled, timeoutMs, etc.).
	 * Subclasses should override and call super.validateCommon() to include these checks.
	 * 
	 * @return list of validation errors, empty if valid
	 */
	protected List<String> validateCommon() {
		List<String> errors = new ArrayList<>();
		if (timeoutMs < 0) {
			errors.add("timeoutMs must be non-negative, got " + timeoutMs);
		}
		return errors;
	}

	/**
	 * Validate all options for this node. Override in subclasses to add node-specific validation.
	 * 
	 * @return ValidationResult containing any errors
	 */
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
