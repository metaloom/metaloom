package io.metaloom.cortex.node.voiceprint;

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
public abstract class VoiceprintNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(VoiceprintNode node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey("voiceprint")
	abstract FilesystemNode<?, ?> kindVoiceprint(VoiceprintNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(VoiceprintNodeOptions.class, VoiceprintNodeOptions.KEY);
	}

	@Provides
	public static VoiceprintNodeOptions options(CortexOptions options) {
		return nodeOptions(options, VoiceprintNodeOptions.KEY, new VoiceprintNodeOptions());
	}

	@Provides
	public static VoiceprintClient voiceprintClient(VoiceprintNodeOptions options) {
		return new VoiceprintClient(options.getVoiceprintHost(), options.getVoiceprintPort());
	}
}
