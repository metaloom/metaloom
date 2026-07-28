package io.metaloom.cortex.common.node;

import io.vertx.core.json.JsonObject;

/**
 * Implemented by nodes whose configuration lives on the <em>pipeline node instance</em>
 * rather than in the worker's YAML.
 *
 * <p>
 * Every other Cortex node reads its options from {@code CortexOptions.getNodes().get(name())},
 * which is per worker: two {@code whisper} nodes in one graph necessarily share a model path.
 * That is the right model for a node whose configuration describes the worker's environment,
 * and the wrong one for a node whose configuration <em>is</em> the work — two {@code script}
 * nodes in one graph run two different scripts.
 * </p>
 *
 * <p>
 * {@code RegistryNodeRegistrar.adapt(...)} calls {@link #configure(JsonObject)} with the raw
 * JSON node definition before wrapping the node in a {@code CortexNodeAdapter}, but only for
 * nodes that implement this interface — so no existing node changes behaviour.
 * </p>
 *
 * <p>
 * <strong>Contract:</strong> an implementing node is mutated by {@code configure} and must
 * therefore never be a Dagger {@code @Singleton}. {@code NodeTaskRunner} builds a node per
 * task through a {@code Provider}, so a fresh instance per task is what the runtime already
 * provides — annotating the node {@code @Singleton} would let two concurrent tasks overwrite
 * each other's configuration.
 * </p>
 */
public interface PipelineConfigurable {

	/**
	 * Apply the per-instance configuration carried by the pipeline node definition.
	 *
	 * <p>
	 * Options from the pipeline definition are flattened onto the top level of {@code nodeDef}
	 * by {@code NodeTaskRunner.toNodeDefinition(...)}, alongside the structural keys
	 * ({@code id}, {@code type}, {@code mode}, {@code blocking}, {@code concurrency},
	 * {@code syncToLoom}, {@code timeoutMs}).
	 * </p>
	 *
	 * @param nodeDef the raw node definition; never null
	 * @throws IllegalStateException when the definition is not a usable configuration. Failing
	 *                               here surfaces as an immediate task failure naming the node,
	 *                               which is far easier to diagnose than a node that silently
	 *                               does nothing for every media item.
	 */
	void configure(JsonObject nodeDef);
}
