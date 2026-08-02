package io.metaloom.cortex.node.filter;

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
import io.metaloom.cortex.llm.LLMProviderModule;

/**
 * Wiring for the {@code filter} kind.
 *
 * <p>
 * The {@code @Binds @IntoMap @StringKey} line is what makes it schedulable, and its absence is
 * precisely why the eight kinds this node replaces never ran.
 * </p>
 *
 * <p>
 * Note that {@link FilterNode} is deliberately <strong>not</strong> {@code @Singleton}: it is a
 * {@code PipelineConfigurable} and is mutated by {@code configure(...)} per task.
 * </p>
 */
@Module(includes = LLMProviderModule.class)
public abstract class FilterNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(FilterNode node);

	@Binds
	@IntoMap
	@StringKey(FilterNode.KIND)
	abstract FilesystemNode<?, ?> kindFilter(FilterNode node);

	/**
	 * One binding per {@link FilterBy} value. Adding a way of filtering is a strategy class and a
	 * line here — never an edit to {@link FilterNode}.
	 */
	@Binds
	@IntoMap
	@FilterByKey(FilterBy.LANGUAGE)
	abstract FilterStrategy bindLanguageStrategy(LanguageFilterStrategy strategy);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(FilterNodeOptions.class, FilterNodeOptions.KEY);
	}

	@Provides
	public static FilterNodeOptions options(CortexOptions options) {
		return nodeOptions(options, FilterNodeOptions.KEY, new FilterNodeOptions());
	}
}
