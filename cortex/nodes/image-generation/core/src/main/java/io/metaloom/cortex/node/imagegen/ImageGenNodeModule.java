package io.metaloom.cortex.node.imagegen;

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
public abstract class ImageGenNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(ImageGenNode node);

	@Binds
	@IntoMap
	@StringKey("imagegen")
	abstract FilesystemNode<?, ?> kindImageGen(ImageGenNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ImageGenNodeOptions.class, ImageGenNodeOptions.KEY);
	}

	@Provides
	public static ImageGenNodeOptions options(CortexOptions options) {
		return nodeOptions(options, ImageGenNodeOptions.KEY, new ImageGenNodeOptions());
	}

	@Provides
	public static ImageGenClient imageGenClient(ImageGenNodeOptions options) {
		return new ImageGenClient(options.getHost(), options.getPort(), options.getGenerateEndpoint(), options.getRemixEndpoint(),
			(int) options.getTimeoutMs());
	}
}
