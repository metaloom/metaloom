package io.metaloom.cortex.api.option.node;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;

/**
 * Common options every node inherits.
 *
 * <p>
 * The {@link ParamDoc} annotations here are the reason the three shared parameters are declared once
 * rather than 34 times. Every hand-written descriptor provider used to restate {@code enabled},
 * {@code processIncomplete} and {@code retryFailed} through a local {@code commonEnabled()} helper —
 * identical prose, copy-pasted, with nothing checking it stayed identical.
 * </p>
 */
public abstract class AbstractNodeOptions<T extends AbstractNodeOptions<T>> implements CortexNodeOptions {

	// The explicit orders make @ParamDoc(order) usable at all. Unordered parameters sort to the end, so
	// without a number here any node that orders one of its own fields would push the three common ones
	// behind it. 10/20/30 simply pins the position they already had.
	@ParamDoc(label = "Enabled", description = "Whether this node is active in the pipeline", order = 10)
	private boolean enabled = true;

	@ParamDoc(label = "Process Incomplete", description = "Process media files that are still being written", order = 20)
	private boolean processIncomplete;

	@ParamDoc(label = "Retry Failed", description = "Retry processing media that previously failed", order = 30)
	private boolean retryFailed;

	/**
	 * Hidden from the contract on purpose: every node has had this field for as long as the base class
	 * has existed, and no descriptor has ever advertised it. Surfacing it now would add a knob to all
	 * 34 edit forms as a side effect of deriving parameters instead of hand-writing them.
	 */
	@ParamDoc(hidden = true)
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
