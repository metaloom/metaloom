package io.metaloom.cortex.node.llm;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class LLMNodeModule extends AbstractNodeModule {

	private static final String KEY = "llm";

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(LLMNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(LLMNodeOptions.class, LLMNodeModule.KEY);
	}

	@Provides
	public static LLMNodeOptions options(CortexOptions options) {
		return nodeOptions(options, KEY, new LLMNodeOptions());
	}
}
