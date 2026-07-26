package io.metaloom.cortex.node.tts;

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
public abstract class TtsNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(TtsNode node);

	@Binds
	@IntoMap
	@StringKey("tts")
	abstract FilesystemNode<?, ?> kindTts(TtsNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(TtsNodeOptions.class, TtsNodeOptions.KEY);
	}

	@Provides
	public static TtsNodeOptions options(CortexOptions options) {
		return nodeOptions(options, TtsNodeOptions.KEY, new TtsNodeOptions());
	}

	@Provides
	public static TtsClient ttsClient(TtsNodeOptions options) {
		return new TtsClient(options.getTtsHost(), options.getTtsPort());
	}
}
