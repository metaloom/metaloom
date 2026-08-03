package io.metaloom.cortex.node.metadata;

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

/**
 * Dagger bindings for the {@code metadata} node.
 *
 * <p>
 * Deliberately <b>not</b> {@code @Singleton}. {@link MetadataNode} is a
 * {@link io.metaloom.cortex.common.node.PipelineConfigurable}, so the registrar mutates each
 * instance with its own node definition; a shared instance would let two concurrent tasks overwrite
 * each other's {@code gpsPolicy}.
 * </p>
 */
@Module
public abstract class MetadataNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(MetadataNode node);

	@Binds
	@IntoMap
	@StringKey(MetadataNode.KIND)
	abstract FilesystemNode<?, ?> kindMetadata(MetadataNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(MetadataNodeOptions.class, MetadataNodeOptions.KEY);
	}

	@Provides
	public static MetadataNodeOptions options(CortexOptions options) {
		return nodeOptions(options, MetadataNodeOptions.KEY, new MetadataNodeOptions());
	}
}
