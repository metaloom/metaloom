package io.metaloom.cortex.common.node;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public abstract class AbstractNodeModule {

	public static <T extends CortexNodeOptions> T nodeOptions(CortexOptions cortexOptions, String key, T defaultOptions) {
		T options = (T) cortexOptions.getNodes().get(key);
		if (options == null) {
			return defaultOptions;
		} else {
			return options;
		}
	}
}
