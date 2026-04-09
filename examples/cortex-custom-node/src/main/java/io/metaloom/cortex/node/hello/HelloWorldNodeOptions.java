package io.metaloom.cortex.node.hello;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

/**
 * Options for the {@link HelloWorldNode}.
 */
public class HelloWorldNodeOptions extends AbstractNodeOptions<HelloWorldNodeOptions> {

	public static final String KEY = "hello-world";

	private boolean computeFileSize = true;
	private boolean computeWordCount = true;

	@Override
	protected HelloWorldNodeOptions self() {
		return this;
	}

	public boolean isComputeFileSize() {
		return computeFileSize;
	}

	public HelloWorldNodeOptions setComputeFileSize(boolean computeFileSize) {
		this.computeFileSize = computeFileSize;
		return self();
	}

	public boolean isComputeWordCount() {
		return computeWordCount;
	}

	public HelloWorldNodeOptions setComputeWordCount(boolean computeWordCount) {
		this.computeWordCount = computeWordCount;
		return self();
	}
}
