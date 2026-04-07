package io.metaloom.cortex.cli.node;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class CustomNodeOptions extends AbstractNodeOptions<CustomNodeOptions> {

	public static final String KEY = "custom";

	@Override
	protected CustomNodeOptions self() {
		return this;
	}
}
