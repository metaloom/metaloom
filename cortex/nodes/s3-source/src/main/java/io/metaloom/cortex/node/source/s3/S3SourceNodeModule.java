package io.metaloom.cortex.node.source.s3;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

/**
 * Dagger wiring for the {@code s3-source} node.
 *
 * <p>As with {@code filesystem-source} there is no {@code @Binds @IntoSet FilesystemNode} binding:
 * this is a pipeline-level source node rather than a {@code FilesystemNode}, so it is not part of
 * the CLI's node set. It is built per pipeline definition by the node factory, which needs the
 * options provided here for its defaults.</p>
 *
 * <p>Connection settings are <em>not</em> here - they are worker-level and live on
 * {@code CortexOptions.getS3()}, wired by the CLI's S3 module.</p>
 */
@Module
public abstract class S3SourceNodeModule extends AbstractNodeModule {

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(S3SourceNodeOptions.class, S3SourceNodeOptions.KEY);
	}

	@Provides
	public static S3SourceNodeOptions options(CortexOptions options) {
		return nodeOptions(options, S3SourceNodeOptions.KEY, new S3SourceNodeOptions());
	}
}
