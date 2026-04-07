package io.metaloom.cortex.node.scene;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class SceneDetectionNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?, ?> bindNode(SceneDetectionNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(SceneDetectionOptions.class, SceneDetectionOptions.KEY);
	}

	@Provides
	public static SceneDetectionOptions options(CortexOptions options) {
		return nodeOptions(options, SceneDetectionOptions.KEY, new SceneDetectionOptions());
	}
}
