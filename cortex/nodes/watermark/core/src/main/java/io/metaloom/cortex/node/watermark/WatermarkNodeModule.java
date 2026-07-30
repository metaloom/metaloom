package io.metaloom.cortex.node.watermark;

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
public abstract class WatermarkNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(WatermarkNode node);

	@Binds
	@IntoMap
	@StringKey("watermark")
	abstract FilesystemNode<?, ?> kindWatermark(WatermarkNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(WatermarkNodeOptions.class, WatermarkNodeOptions.KEY);
	}

	@Provides
	public static WatermarkNodeOptions options(CortexOptions options) {
		return nodeOptions(options, WatermarkNodeOptions.KEY, new WatermarkNodeOptions());
	}
}
