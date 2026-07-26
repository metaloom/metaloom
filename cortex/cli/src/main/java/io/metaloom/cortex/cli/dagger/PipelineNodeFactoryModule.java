package io.metaloom.cortex.cli.dagger;

import java.util.Map;

import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNodeOptions;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.pipeline.loader.NodeRegistrar;
import io.metaloom.cortex.pipeline.loader.RegistryNodeFactory;

/**
 * Dagger module that wires the pipeline node registry.
 *
 * <p>The registry itself ({@link RegistryNodeFactory}) is provided empty and
 * bound as the {@link NodeFactory}. It is populated imperatively during Cortex
 * bootstrap by the {@link NodeRegistrar} (see
 * {@code CortexBootstrapInitializer#init}), from the concrete nodes contributed
 * by {@link NodeCollectionModule} through the {@code Map<String, Provider<FilesystemNode>>}
 * multibinding. Adding a node kind is therefore a one-line {@code @IntoMap @StringKey}
 * binding in that node's own module — no edit here.</p>
 */
@Module(includes = NodeCollectionModule.class)
public abstract class PipelineNodeFactoryModule {

	/** The registry {@link RegistryNodeFactory} is the {@link NodeFactory}; a single shared, initially empty instance. */
	@Binds
	@Singleton
	abstract NodeFactory bindNodeFactory(RegistryNodeFactory factory);

	/**
	 * The registrar that fills the shared registry at bootstrap. It receives the
	 * same {@link RegistryNodeFactory} singleton that {@link #bindNodeFactory} exposes
	 * as the {@link NodeFactory}, plus the node-kind multibinding and the source-node
	 * collaborators.
	 */
	@Provides
	@Singleton
	static NodeRegistrar provideNodeRegistrar(
		RegistryNodeFactory factory,
		Map<String, Provider<FilesystemNode<?, ?>>> nodeKinds,
		LoomMediaLoader mediaLoader,
		FilesystemSourceNodeOptions fsSourceOptions,
		CortexOptions cortexOptions) {
		return new RegistryNodeRegistrar(factory, nodeKinds, mediaLoader, fsSourceOptions, cortexOptions);
	}
}
