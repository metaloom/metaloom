package io.metaloom.cortex.node.color;

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
public abstract class DominantColorNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(DominantColorNode node);

	/**
	 * Without this map binding the node exists but is never schedulable - the executable-kind
	 * registry is built from this map alone.
	 */
	@Binds
	@IntoMap
	@StringKey("dominant-color")
	abstract FilesystemNode<?, ?> kindDominantColor(DominantColorNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(DominantColorNodeOptions.class, DominantColorNodeOptions.KEY);
	}

	@Provides
	public static DominantColorNodeOptions options(CortexOptions options) {
		return nodeOptions(options, DominantColorNodeOptions.KEY, new DominantColorNodeOptions());
	}
}
