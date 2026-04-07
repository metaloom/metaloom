package io.metaloom.cortex.api.option.node;

public abstract class AbstractNodeOptions<T extends AbstractNodeOptions<T>> implements CortexNodeOptions {

	private boolean enabled = true;

	private boolean processIncomplete;

	private boolean retryFailed;

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

}
