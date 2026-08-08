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

	/**
	 * The three metadata strategies. They take no {@code LLMProvider}, which is the point: a graph
	 * that only splits images from video, or last month's files from last year's, runs on a worker
	 * with no model backend reachable at all.
	 */
	@Binds
	@IntoMap
	@FilterByKey(FilterBy.MIME)
	abstract FilterStrategy bindMimeStrategy(MimeFilterStrategy strategy);

	@Binds
	@IntoMap
	@FilterByKey(FilterBy.SIZE)
	abstract FilterStrategy bindSizeStrategy(SizeFilterStrategy strategy);

	@Binds
	@IntoMap
	@FilterByKey(FilterBy.DATE)
	abstract FilterStrategy bindDateStrategy(DateFilterStrategy strategy);

	/**
	 * The two decision strategies. They need no model either, but unlike the three above they do need
	 * Loom: a rating and a tag are things a person recorded, and only Loom knows them. Both answer
	 * {@code other} rather than failing when the asset is unknown or Loom is unreachable, so a worker
	 * running offline degrades to "nothing matched" instead of aborting the task.
	 */
	@Binds
	@IntoMap
	@FilterByKey(FilterBy.RATING)
	abstract FilterStrategy bindRatingStrategy(RatingFilterStrategy strategy);

	@Binds
	@IntoMap
	@FilterByKey(FilterBy.TAG)
	abstract FilterStrategy bindTagStrategy(TagFilterStrategy strategy);

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
