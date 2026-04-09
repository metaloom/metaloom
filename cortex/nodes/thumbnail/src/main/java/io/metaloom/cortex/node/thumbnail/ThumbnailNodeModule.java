package io.metaloom.cortex.node.thumbnail;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class ThumbnailNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(ThumbnailNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ThumbnailNodeOptions.class, ThumbnailNodeOptions.KEY);
	}

	@Provides
	public static ThumbnailNodeOptions options(CortexOptions options) {
		return nodeOptions(options, ThumbnailNodeOptions.KEY, new ThumbnailNodeOptions());
	}

}
