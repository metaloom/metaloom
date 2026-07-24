package io.metaloom.cortex.node.whisper;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class WhisperNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindWhisperNode(WhisperNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(WhisperOptions.class, WhisperOptions.KEY);
	}

	@Provides
	public static WhisperOptions options(CortexOptions options) {
		return nodeOptions(options, WhisperOptions.KEY, new WhisperOptions());
	}

}
