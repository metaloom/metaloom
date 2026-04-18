package io.metaloom.cortex.node.dedup;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class DedupNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindHashDedupNode(HashDedupNode node);

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindFingerprintDedupNode(FingerprintDedupNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(DedupNodeOptions.class, "dedup");
	}

	@Provides
	public static DedupNodeOptions options(CortexOptions options) {
		return nodeOptions(options, "dedup", new DedupNodeOptions());
	}

}
