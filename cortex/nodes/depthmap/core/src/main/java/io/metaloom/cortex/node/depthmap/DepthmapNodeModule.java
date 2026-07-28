package io.metaloom.cortex.node.depthmap;

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
public abstract class DepthmapNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(DepthmapNode node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey("depthmap")
	abstract FilesystemNode<?, ?> kindDepthmap(DepthmapNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(DepthmapNodeOptions.class, DepthmapNodeOptions.KEY);
	}

	@Provides
	public static DepthmapNodeOptions options(CortexOptions options) {
		return nodeOptions(options, DepthmapNodeOptions.KEY, new DepthmapNodeOptions());
	}

	@Provides
	public static DepthmapClient depthmapClient(DepthmapNodeOptions options) {
		return new DepthmapClient(options.getDepthHost(), options.getDepthPort(), (int) options.getTimeoutMs());
	}
}
