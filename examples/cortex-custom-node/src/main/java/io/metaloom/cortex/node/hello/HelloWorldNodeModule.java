package io.metaloom.cortex.node.hello;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

/**
 * Dagger module that registers the {@link HelloWorldNode} with the Cortex pipeline.
 *
 * <p>This module:
 * <ul>
 *   <li>Binds the node into the multibinding set so the pipeline discovers it.</li>
 *   <li>Provides the node's options (read from the global config, or defaults).</li>
 *   <li>Registers the option deserializer so YAML/JSON config can populate the options.</li>
 * </ul>
 */
@Module
public abstract class HelloWorldNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?, ?> bindHelloWorldNode(HelloWorldNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(HelloWorldNodeOptions.class, HelloWorldNodeOptions.KEY);
	}

	@Provides
	public static HelloWorldNodeOptions options(CortexOptions options) {
		return nodeOptions(options, HelloWorldNodeOptions.KEY, new HelloWorldNodeOptions());
	}
}
