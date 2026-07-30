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

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindFingerprintDedupApplyNode(FingerprintDedupApplyNode node);

	// Executable kind map. NOTE: the descriptor kind ("hash-dedup") historically differed from the
	// node's own name ("sha512-dedup"); both ids are bound to the same node so either resolves and a
	// "hash-dedup" node placed in the editor is runnable (spec NODE_DEDUP_PLAN.md §8).
	@Binds
	@IntoMap
	@StringKey("sha512-dedup")
	abstract FilesystemNode<?, ?> kindHashDedup(HashDedupNode node);

	@Binds
	@IntoMap
	@StringKey("hash-dedup")
	abstract FilesystemNode<?, ?> kindHashDedupAlias(HashDedupNode node);

	@Binds
	@IntoMap
	@StringKey("fingerprint-dedup")
	abstract FilesystemNode<?, ?> kindFingerprintDedup(FingerprintDedupNode node);

	@Binds
	@IntoMap
	@StringKey("fingerprint-dedup-apply")
	abstract FilesystemNode<?, ?> kindFingerprintDedupApply(FingerprintDedupApplyNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(DedupNodeOptions.class, "dedup");
	}

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo fingerprintDedupOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(FingerprintDedupDiscoverOptions.class, "fingerprint-dedup");
	}

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo fingerprintDedupApplyOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(DedupNodeOptions.class, "fingerprint-dedup-apply");
	}

	@Provides
	public static DedupNodeOptions options(CortexOptions options) {
		return nodeOptions(options, "dedup", new DedupNodeOptions());
	}

	@Provides
	public static FingerprintDedupDiscoverOptions fingerprintDedupOptions(CortexOptions options) {
		return nodeOptions(options, "fingerprint-dedup", new FingerprintDedupDiscoverOptions());
	}

}
