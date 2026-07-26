package io.metaloom.cortex.node.hash;

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

	// Pipeline node-kind registry: each entry advertises an executable kind to Loom
	// and is resolved lazily (Provider) only when a task of that kind arrives.
	@Binds
	@IntoMap
	@StringKey("sha512")
	abstract FilesystemNode<?, ?> kindSHA512(SHA512Node node);

	@Binds
	@IntoMap
	@StringKey("sha256")
	abstract FilesystemNode<?, ?> kindSHA256(SHA256Node node);

	@Binds
	@IntoMap
	@StringKey("md5")
	abstract FilesystemNode<?, ?> kindMD5(MD5Node node);

	@Binds
	@IntoMap
	@StringKey("chunk-hash")
	abstract FilesystemNode<?, ?> kindChunkHash(ChunkHashNode node);

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
