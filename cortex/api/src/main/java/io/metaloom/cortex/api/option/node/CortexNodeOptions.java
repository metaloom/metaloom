package io.metaloom.cortex.api.option.node;

public interface CortexNodeOptions {

	boolean isEnabled();

	void setEnabled(boolean flag);

	long getTimeoutMs();

	void setTimeoutMs(long timeoutMs);

}
