package io.metaloom.cortex.node.captioning;

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
public abstract class CaptioningNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(CaptioningNode node);

	@Binds
	@IntoMap
	@StringKey("captioning")
	abstract FilesystemNode<?, ?> kindCaptioning(CaptioningNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(CaptioningNodeOptions.class, "captioning");
	}

	@Provides
	public static CaptioningNodeOptions options(CortexOptions options) {
		return nodeOptions(options, "captioning", new CaptioningNodeOptions());
	}

	@Provides
	public static SmolVLMClient smolVLMClient(CaptioningNodeOptions options) {
		return new SmolVLMClient(options.getSmolVLMHost(), options.getSmolVLMPort());
	}

	@Provides
	public static VideoVLMClient videoVLMClient(CaptioningNodeOptions options) {
		return new VideoVLMClient(options.getVideoEndpointUrl(), options.getVideoModel(), options.getVideoApiKey(), options.getMaxTokens(),
			options.getTemperature());
	}
}
