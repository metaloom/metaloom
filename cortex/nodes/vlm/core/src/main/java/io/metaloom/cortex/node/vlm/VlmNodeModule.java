package io.metaloom.cortex.node.vlm;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class VlmNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(VlmNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(VlmNodeOptions.class, VlmNodeOptions.KEY);
	}

	@Provides
	public static VlmNodeOptions options(CortexOptions options) {
		return nodeOptions(options, VlmNodeOptions.KEY, new VlmNodeOptions());
	}

	/**
	 * The endpoint seam: production gets a real HTTP client, tests substitute one pointed at a mock server.
	 */
	@Provides
	public static VlmChatClient vlmChatClient(VlmNodeOptions options) {
		return new VlmChatClient(options.getEndpointUrl(), options.getApiKey());
	}
}
