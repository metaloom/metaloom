package io.metaloom.cortex.node.guard;

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
 * Dagger wiring for the guard node.
 *
 * <p>
 * Deliberately does <em>not</em> include {@code LLMProviderModule}. The node reaches the same
 * OpenAI-compatible backends as {@code llm} and {@code translate}, but through its own client rather
 * than through {@code LLMProvider} — see {@link GuardClient} for why — and a second unqualified
 * {@code LLMProvider} binding would be a compile error rather than a harmless duplicate.
 * </p>
 */
@Module
public abstract class GuardNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(GuardNode node);

	@Binds
	@IntoMap
	@StringKey(GuardNode.KIND)
	abstract FilesystemNode<?, ?> kindGuard(GuardNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(GuardNodeOptions.class, GuardNodeOptions.KEY);
	}

	@Provides
	public static GuardNodeOptions options(CortexOptions options) {
		return nodeOptions(options, GuardNodeOptions.KEY, new GuardNodeOptions());
	}

	/**
	 * The endpoint seam: production gets a real HTTP client, tests substitute a mock or one pointed
	 * at a stub server.
	 */
	@Provides
	public static GuardClient guardClient(GuardNodeOptions options) {
		return new GuardClient(options.openaiUrl(), options.getApiKey());
	}
}
