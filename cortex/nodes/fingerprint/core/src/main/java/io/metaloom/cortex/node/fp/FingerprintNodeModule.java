package io.metaloom.cortex.node.fp;

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
public abstract class FingerprintNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(FingerprintNode node);

	@Binds
	@IntoMap
	@StringKey("fingerprint")
	abstract FilesystemNode<?, ?> kindFingerprint(FingerprintNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(FingerprintNodeOptions.class, "fingerprint");
	}

	@Provides
	public static FingerprintNodeOptions options(CortexOptions options) {
		return nodeOptions(options, "fingerprint", new FingerprintNodeOptions());
	}

}
