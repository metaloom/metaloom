package io.metaloom.cortex.node.tika;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class TikaNodeModule extends AbstractNodeModule {

	private static final String KEY = "tika";

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(TikaNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(TikaNodeOptions.class, TikaNodeModule.KEY);
	}

	@Provides
	public static TikaNodeOptions options(CortexOptions options) {
		return nodeOptions(options, KEY, new TikaNodeOptions());
	}
}
