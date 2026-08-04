package io.metaloom.cortex.node.tag;

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
 * Wiring for the {@code tag} kind.
 *
 * <p>
 * The {@code @Binds @IntoMap @StringKey} line is what makes the node schedulable at all; without it a
 * graph using the kind saves, validates, dispatches and then fails at the worker.
 * </p>
 *
 * <p>
 * {@link TagNode} is deliberately <strong>not</strong> {@code @Singleton}: it is a
 * {@code PipelineConfigurable} and {@code configure(...)} mutates it per task.
 * </p>
 */
@Module
public abstract class TagNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(TagNode node);

	@Binds
	@IntoMap
	@StringKey(TagNode.KIND)
	abstract FilesystemNode<?, ?> kindTag(TagNode node);

	/**
	 * One binding per {@link TagBy} value. Adding a way of tagging is a strategy class and a line
	 * here - never an edit to {@link TagNode}.
	 */
	@Binds
	@IntoMap
	@TagByKey(TagBy.RULES)
	abstract TagStrategy bindRulesStrategy(RulesTagStrategy strategy);

	@Binds
	@IntoMap
	@TagByKey(TagBy.LABELS)
	abstract TagStrategy bindLabelsStrategy(LabelsTagStrategy strategy);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(TagNodeOptions.class, TagNodeOptions.KEY);
	}

	@Provides
	public static TagNodeOptions options(CortexOptions options) {
		return nodeOptions(options, TagNodeOptions.KEY, new TagNodeOptions());
	}
}
