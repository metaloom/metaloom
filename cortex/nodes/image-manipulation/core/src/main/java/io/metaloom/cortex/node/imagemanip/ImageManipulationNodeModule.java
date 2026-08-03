package io.metaloom.cortex.node.imagemanip;

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
public abstract class ImageManipulationNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(ImageManipulationNode node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey(ImageManipulationNode.KIND)
	abstract FilesystemNode<?, ?> kindImageManipulation(ImageManipulationNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ImageManipulationNodeOptions.class, ImageManipulationNodeOptions.KEY);
	}

	@Provides
	public static ImageManipulationNodeOptions options(CortexOptions options) {
		return nodeOptions(options, ImageManipulationNodeOptions.KEY, new ImageManipulationNodeOptions());
	}
}
