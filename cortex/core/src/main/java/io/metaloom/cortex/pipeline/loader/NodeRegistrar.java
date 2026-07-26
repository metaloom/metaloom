package io.metaloom.cortex.pipeline.loader;

/**
 * Populates the pipeline node registry ({@link RegistryNodeFactory}) with the
 * concrete node kinds this worker can execute.
 *
 * <p>This is a seam between {@code cortex/core} (which owns the bootstrap
 * sequence and the registry) and the assembly module in {@code cortex/cli}
 * (which is the only place that sees every concrete node type plus the
 * {@code CortexNodeAdapter}). The bootstrap initializer invokes
 * {@link #registerAll()} <strong>before</strong> the Loom control channel
 * starts, so the registration message advertises the fully-populated
 * {@link RegistryNodeFactory#registeredTypes()} rather than an empty or partial
 * set.</p>
 *
 * <p>Implementations must be idempotent: {@link #registerAll()} may be called
 * more than once (e.g. across a reconnect-triggered re-bootstrap) and must not
 * duplicate or corrupt the registry.</p>
 */
public interface NodeRegistrar {

	/**
	 * Register every executable node kind (source nodes and processing nodes)
	 * into the shared registry. Idempotent.
	 */
	void registerAll();

}
