package io.metaloom.cortex.node.source.cloud;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

/**
 * Dagger wiring for the {@code gdrive-source} and {@code onedrive-source} nodes.
 *
 * <p>As with the other source nodes there is no {@code @Binds @IntoSet FilesystemNode} and no
 * {@code @IntoMap @StringKey} binding: these are pipeline-level source nodes rather than
 * {@code FilesystemNode}s, so they are not part of the CLI's node set and not part of the
 * executable-kind multibinding. They are built per pipeline definition by the node factory, which
 * needs the options provided here for its defaults.</p>
 *
 * <p>One module, two kinds - the two option types are the only per-provider state at this level.
 * Credentials are <em>not</em> here: they are worker-level and live on
 * {@code CortexOptions.getGdrive()} / {@code getOnedrive()}, wired by the CLI's cloud module.</p>
 */
@Module
public abstract class CloudSourceNodeModule extends AbstractNodeModule {

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo gdriveOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(GDriveSourceNodeOptions.class, GDriveSourceNodeOptions.KEY);
	}

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo onedriveOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(OneDriveSourceNodeOptions.class, OneDriveSourceNodeOptions.KEY);
	}

	@Provides
	public static GDriveSourceNodeOptions gdriveOptions(CortexOptions options) {
		return nodeOptions(options, GDriveSourceNodeOptions.KEY, new GDriveSourceNodeOptions());
	}

	@Provides
	public static OneDriveSourceNodeOptions onedriveOptions(CortexOptions options) {
		return nodeOptions(options, OneDriveSourceNodeOptions.KEY, new OneDriveSourceNodeOptions());
	}
}
