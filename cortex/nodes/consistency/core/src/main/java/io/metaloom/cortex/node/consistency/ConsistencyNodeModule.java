package io.metaloom.cortex.node.consistency;

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
public abstract class ConsistencyNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(ConsistencyNode node);

	@Binds
	@IntoMap
	@StringKey("consistency")
	abstract FilesystemNode<?, ?> kindConsistency(ConsistencyNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ConsistencyNodeOptions.class, "consistency");
	}

	@Provides
	public static ConsistencyNodeOptions options(CortexOptions options) {
		return nodeOptions(options, "consistency", new ConsistencyNodeOptions());
	}
}
