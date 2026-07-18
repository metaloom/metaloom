package io.metaloom.cortex.node.source.fs;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

/**
 * Dagger wiring for the {@code filesystem-source} node.
 *
 * <p>Unlike the processing node modules there is no {@code @Binds @IntoSet
 * FilesystemNode} binding here: this is a pipeline-level source node, not a
 * {@code FilesystemNode}, so it is not part of the CLI's node set. It is
 * instantiated per pipeline definition by the node factory, which needs the
 * options provided below for its defaults.</p>
 */
@Module
public abstract class FilesystemSourceNodeModule extends AbstractNodeModule {

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(FilesystemSourceNodeOptions.class, FilesystemSourceNodeOptions.KEY);
	}

	@Provides
	public static FilesystemSourceNodeOptions options(CortexOptions options) {
		return nodeOptions(options, FilesystemSourceNodeOptions.KEY, new FilesystemSourceNodeOptions());
	}
}
