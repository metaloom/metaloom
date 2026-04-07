package io.metaloom.cortex.common.option;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public class CortexNodeOptionDeserializerInfo {

	private Class<? extends CortexNodeOptions> clazz;
	private String prefix;

	public CortexNodeOptionDeserializerInfo(Class<? extends CortexNodeOptions> clazz, String prefix) {
		this.clazz = clazz;
		this.prefix = prefix;
	}

	public Class<? extends CortexNodeOptions> getOptionClazz() {
		return clazz;
	}

	public String getOptionPrefix() {
		return prefix;
	}
}
