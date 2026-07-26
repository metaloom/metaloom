package io.metaloom.cortex.node.quality;

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
public abstract class QualityNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(QualityNode node);

	@Binds
	@IntoMap
	@StringKey("quality")
	abstract FilesystemNode<?, ?> kindQuality(QualityNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(QualityNodeOptions.class, QualityNodeOptions.KEY);
	}

	@Provides
	public static QualityNodeOptions options(CortexOptions options) {
		return nodeOptions(options, QualityNodeOptions.KEY, new QualityNodeOptions());
	}

}
