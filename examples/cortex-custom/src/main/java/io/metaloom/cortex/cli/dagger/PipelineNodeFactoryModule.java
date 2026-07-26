package io.metaloom.cortex.cli.dagger;

import java.util.Map;

import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.node.hello.HelloWorldNode;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.pipeline.loader.NodeRegistrar;
import io.metaloom.cortex.pipeline.loader.RegistryNodeFactory;

/**
 * Dagger module that wires the pipeline node registry for the custom Cortex CLI.
 *
 * <p>This mirrors the module of the same name in {@code cortex-cli}: the empty
 * {@link RegistryNodeFactory} is bound as the {@link NodeFactory}, and a
 * {@link NodeRegistrar} fills it at bootstrap. The built-in node kinds come from
 * the {@code Map<String, Provider<FilesystemNode>>} multibinding that every node
 * module contributes to, so there is nothing to list here for them.</p>
 *
 * <p>The extension point a downstream instance uses is the last line of
 * {@link #provideNodeRegistrar}: register your own node (here the example's
 * {@link HelloWorldNode} from {@code cortex-custom-node}) under its own type,
 * alongside the built-ins. The {@link Provider} keeps it uninstantiated until a
 * {@code hello-world} task arrives.</p>
 */
@Module(includes = NodeCollectionModule.class)
public abstract class PipelineNodeFactoryModule {

	@Binds
	@Singleton
	abstract NodeFactory bindNodeFactory(RegistryNodeFactory factory);

	@Provides
	@Singleton
	static NodeRegistrar provideNodeRegistrar(
		RegistryNodeFactory factory,
		Map<String, Provider<FilesystemNode<?, ?>>> nodeKinds,
		Provider<HelloWorldNode> helloWorld,
		CortexOptions cortexOptions) {
		return () -> {
			// Built-in kinds, contributed by each node module via @IntoMap @StringKey.
			nodeKinds.forEach((kind, provider) ->
				factory.register(kind, def -> adapt(provider.get(), def, cortexOptions)));
			// The example's own custom node, registered under its own type.
			factory.register("hello-world", def -> adapt(helloWorld.get(), def, cortexOptions));
		};
	}

	private static PipelineNode adapt(FilesystemNode<?, ?> wrapped, io.vertx.core.json.JsonObject nodeDef, CortexOptions cortexOptions) {
		String id = nodeDef.getString("id", wrapped.name());
		NodeMode mode = NodeMode.valueOf(nodeDef.getString("mode", "PARALLEL"));
		boolean blocking = nodeDef.getBoolean("blocking", true);
		int concurrency = nodeDef.getInteger("concurrency", 1);
		boolean syncToLoom = nodeDef.getBoolean("syncToLoom", false);

		// Validate concurrency
		if (concurrency <= 0) {
			throw new IllegalStateException("Node '" + id + "': concurrency must be positive, got " + concurrency);
		}

		// Use timeout from JSON if specified, otherwise fall back to default from config
		long timeoutMs = nodeDef.getLong("timeoutMs", 0L);
		if (timeoutMs == 0) {
			String nodeType = nodeDef.getString("type", wrapped.name());
			timeoutMs = cortexOptions.getDefaultTimeoutMs(nodeType);
		}

		// Validate timeout
		if (timeoutMs < 0) {
			throw new IllegalStateException("Node '" + id + "': timeoutMs must be non-negative, got " + timeoutMs);
		}

		CortexNodeAdapter adapter = new CortexNodeAdapter(id, wrapped, mode, blocking, concurrency, timeoutMs);
		if (syncToLoom) {
			adapter.setSyncToLoom(true);
		}

		// Validate node options if available in cortexOptions
		if (cortexOptions != null && cortexOptions.getNodes() != null) {
			CortexNodeOptions nodeOptions = cortexOptions.getNodes().get(wrapped.name());
			if (nodeOptions != null) {
				ValidationResult result = nodeOptions.validate();
				if (result.isInvalid()) {
					throw new IllegalStateException("Node '" + id + "' options validation failed: " + String.join("; ", result.getErrors()));
				}
			}
		}

		return adapter;
	}
}
