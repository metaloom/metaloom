package io.metaloom.cortex.node.hash;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class HashNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindSHA512Node(SHA512Node node);

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindSHA256Node(SHA256Node node);

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindMD5Node(MD5Node node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(HashNodeOptions.class, HashNodeOptions.KEY);
	}

	@Provides
	public static HashNodeOptions options(CortexOptions options) {
		return nodeOptions(options, HashNodeOptions.KEY, new HashNodeOptions());
	}
}
