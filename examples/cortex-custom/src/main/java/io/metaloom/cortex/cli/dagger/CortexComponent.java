package io.metaloom.cortex.cli.dagger;

import javax.annotation.Nullable;
import javax.inject.Named;

import dagger.BindsInstance;
import dagger.Component;
import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.option.CortexOptionsLoader;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.pipeline.loader.NodeRegistrar;

import javax.inject.Singleton;

/**
 * Dagger object graph for the custom Cortex <b>daemon</b>.
 *
 * <p>
 * This example deliberately omits the picocli command layer of the real Cortex CLI: it wires just enough to build a {@link Cortex} instance and hand it
 * back to {@link io.metaloom.cortex.cli.CortexCustomMain}, which runs it as a long-lived worker connected to a Loom backend.
 * </p>
 *
 * <p>
 * {@link S3Module} is required even for a worker that never touches object storage: the monitoring service and the bootstrap initializer take the S3
 * event sources as constructor arguments, and with no S3 configuration those sources simply stay inactive.
 * </p>
 */
@Singleton
@Component(modules = { CortexBindModule.class, CortexMediaModule.class, NodeCollectionModule.class,
	PipelineNodeFactoryModule.class, CortexClientModule.class, S3Module.class })
public interface CortexComponent {

	Cortex cortex();

	LoomMediaLoader loader();

	CortexOptions options();

	CortexOptionsLoader optionsLoader();

	/**
	 * The registry of node kinds this instance can execute — the ground truth for what the worker announces to Loom.
	 *
	 * @return the node factory
	 */
	NodeFactory nodeFactory();

	/**
	 * Populates {@link #nodeFactory()} with this instance's executable node kinds. In a running daemon the bootstrap
	 * initializer invokes {@code registerAll()} before registering with Loom.
	 *
	 * @return the node registrar
	 */
	NodeRegistrar nodeRegistrar();

	@Component.Builder
	interface Builder {

		/**
		 * Inject the cortex options.
		 * 
		 * @param options
		 * @return
		 */
		@BindsInstance
		Builder options(@Named("default-options") @Nullable CortexOptions options);

		/**
		 * Build the component.
		 * 
		 * @return
		 */
		CortexComponent build();

	}

}
