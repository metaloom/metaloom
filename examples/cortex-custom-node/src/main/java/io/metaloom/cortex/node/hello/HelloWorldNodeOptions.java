package io.metaloom.cortex.node.hello;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

/**
 * Options for the {@link HelloWorldNode}.
 *
 * <p>
 * Each field becomes a parameter in the editor's node form. The key and the type are the field's own
 * name and type, and the default is whatever a default-constructed instance holds - so
 * {@code computeFileSize = true} below needs no restating anywhere. {@link ParamDoc} supplies only
 * the label and help text, which reflection cannot invent.
 * </p>
 *
 * <p>
 * The three parameters every node inherits - {@code enabled}, {@code processIncomplete} and
 * {@code retryFailed} - come from {@code AbstractNodeOptions} and are declared once there.
 * </p>
 */
public class HelloWorldNodeOptions extends AbstractNodeOptions<HelloWorldNodeOptions> {

	public static final String KEY = "hello-world";

	@ParamDoc(label = "Compute File Size", description = "Emit the file_size output port")
	private boolean computeFileSize = true;

	@ParamDoc(label = "Compute Word Count", description = "Emit the word_count output port")
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
