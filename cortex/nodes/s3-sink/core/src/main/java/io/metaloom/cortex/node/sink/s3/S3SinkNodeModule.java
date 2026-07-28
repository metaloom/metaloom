package io.metaloom.cortex.node.sink.s3;

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
 * Dagger wiring for the {@code s3-sink} node.
 *
 * <p>⚠️ {@link S3SinkNode} is deliberately <strong>not</strong> {@code @Singleton}: it is mutated
 * by {@code configure(...)} per task, so two concurrent sink nodes writing to different buckets
 * must not share an instance. The kind map holds a {@code Provider}, which already yields a fresh
 * node per task; {@code PipelineConfigurableTest} pins that.</p>
 */
@Module
public abstract class S3SinkNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(S3SinkNode node);

	/** Without this map binding the node exists but is never schedulable. */
	@Binds
	@IntoMap
	@StringKey(S3SinkNode.KIND)
	abstract FilesystemNode<?, ?> kindS3Sink(S3SinkNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(S3SinkNodeOptions.class, S3SinkNodeOptions.KEY);
	}

	@Provides
	public static S3SinkNodeOptions options(CortexOptions options) {
		return nodeOptions(options, S3SinkNodeOptions.KEY, new S3SinkNodeOptions());
	}
}
