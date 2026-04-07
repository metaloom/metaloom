package io.metaloom.cortex.node.loom;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class LoomNodeOptions extends AbstractNodeOptions<LoomNodeOptions> {

	public static final String KEY = "loom";

	@Override
	protected LoomNodeOptions self() {
		return this;
	}

}
