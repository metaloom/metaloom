package io.metaloom.cortex.node.translate;

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

@Module(includes = LLMProviderModule.class)
public abstract class TranslateNodeModule extends AbstractNodeModule {

	private static final String KEY = TranslateNodeOptions.KEY;

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(TranslateNode node);

	/** Without this map binding the node exists but is never schedulable. */
	@Binds
	@IntoMap
	@StringKey("translate")
	abstract FilesystemNode<?, ?> kindTranslate(TranslateNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(TranslateNodeOptions.class, KEY);
	}

	@Provides
	public static TranslateNodeOptions options(CortexOptions options) {
		return nodeOptions(options, KEY, new TranslateNodeOptions());
	}
}
