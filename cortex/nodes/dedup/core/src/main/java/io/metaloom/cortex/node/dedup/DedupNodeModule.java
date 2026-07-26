package io.metaloom.cortex.node.dedup;

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
public abstract class DedupNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindHashDedupNode(HashDedupNode node);

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindFingerprintDedupNode(FingerprintDedupNode node);

	// Only HashDedup is advertised as an executable kind; FingerprintDedupNode is a
	// "not implemented" stub and is deliberately kept out of the pipeline registry.
	@Binds
	@IntoMap
	@StringKey("sha512-dedup")
	abstract FilesystemNode<?, ?> kindHashDedup(HashDedupNode node);

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
