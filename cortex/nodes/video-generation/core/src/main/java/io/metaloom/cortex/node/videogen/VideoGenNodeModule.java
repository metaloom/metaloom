package io.metaloom.cortex.node.videogen;

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
public abstract class VideoGenNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(VideoGenNode node);

	@Binds
	@IntoMap
	@StringKey("videogen")
	abstract FilesystemNode<?, ?> kindVideoGen(VideoGenNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(VideoGenNodeOptions.class, VideoGenNodeOptions.KEY);
	}

	@Provides
	public static VideoGenNodeOptions options(CortexOptions options) {
		return nodeOptions(options, VideoGenNodeOptions.KEY, new VideoGenNodeOptions());
	}

	@Provides
	public static VideoGenClient videoGenClient(VideoGenNodeOptions options) {
		return new VideoGenClient(options.getHost(), options.getPort(), options.getGenerateEndpoint(), options.getAnimateEndpoint(),
			(int) options.getTimeoutMs());
	}
}
