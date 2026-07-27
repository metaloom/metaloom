package io.metaloom.cortex.node.sentiment;

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
public abstract class SentimentNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(SentimentNode node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey("sentiment")
	abstract FilesystemNode<?, ?> kindSentiment(SentimentNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(SentimentNodeOptions.class, SentimentNodeOptions.KEY);
	}

	@Provides
	public static SentimentNodeOptions options(CortexOptions options) {
		return nodeOptions(options, SentimentNodeOptions.KEY, new SentimentNodeOptions());
	}

	@Provides
	public static SentimentClient sentimentClient(SentimentNodeOptions options) {
		return new SentimentClient(options.getSentimentHost(), options.getSentimentPort());
	}
}
