package io.metaloom.cortex.node.loom;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class LoomNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(LoomNode node);

	@Binds
	@IntoMap
	@StringKey("loom")
	abstract FilesystemNode<?, ?> kindLoom(LoomNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(LoomNodeOptions.class, LoomNodeOptions.KEY);
	}

	@Provides
	public static LoomNodeOptions options(CortexOptions options) {
		return nodeOptions(options, LoomNodeOptions.KEY, new LoomNodeOptions());
	}
}
