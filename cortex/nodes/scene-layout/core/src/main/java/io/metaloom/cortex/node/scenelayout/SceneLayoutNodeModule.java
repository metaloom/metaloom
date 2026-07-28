package io.metaloom.cortex.node.scenelayout;

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
public abstract class SceneLayoutNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(SceneLayoutNode node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey("scene-layout")
	abstract FilesystemNode<?, ?> kindSceneLayout(SceneLayoutNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(SceneLayoutNodeOptions.class, SceneLayoutNodeOptions.KEY);
	}

	@Provides
	public static SceneLayoutNodeOptions options(CortexOptions options) {
		return nodeOptions(options, SceneLayoutNodeOptions.KEY, new SceneLayoutNodeOptions());
	}
}
