package io.metaloom.cortex.cli.node;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class CustomNodeModule extends AbstractNodeModule {
	
	private static final String KEY = "custom";

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?, ?> bindNode(CustomNode node);
	
	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(CustomNodeOptions.class, CustomNodeOptions.KEY);
	}
	
	@Provides
	public static CustomNodeOptions options(CortexOptions options) {
		return nodeOptions(options, KEY, new CustomNodeOptions());
	}
}
