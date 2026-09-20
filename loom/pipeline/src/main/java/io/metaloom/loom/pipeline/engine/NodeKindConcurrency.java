package io.metaloom.loom.pipeline.engine;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;

/**
 * Apply each node kind's declared concurrency to a run.
 *
 * <p>
 * {@code NodeSpec.defaultConcurrency} has been on every node since the annotation was written -
 * {@code whisper} and {@code llm} declare 1, {@code sha512} declares 4 - and it reached the
 * generated descriptor JSON and stopped. Nothing read it, and
 * {@link PipelineRunEngine#setMaxInFlightForKind} had a whole test class and no production caller.
 * The two were built to meet and were never introduced.
 * </p>
 *
 * <p>
 * What the gap cost, and why the absence was invisible: a differential source enumerates one
 * changed file per run, so an unchanged library exercises a concurrency of one whether or not
 * anything enforces it, and the deployment looks healthy indefinitely. The first batch carrying
 * twenty-three items dispatched twenty-three {@code whisper} tasks at once; the worker built a
 * 1.55 GB CUDA context per task and the sixth exhausted an 8 GB card. ggml does not return an
 * error from a failed {@code cudaMalloc} - it calls {@code GGML_ASSERT} and {@code abort()} - so
 * the whole worker process died mid-run and was restarted, repeatedly, transcribing nothing.
 * </p>
 *
 * <p>
 * The ceiling is per kind and per run, which is what the engine can express. A fleet-wide cap
 * across concurrent runs would have to be owned by the registry, and is worth building the day two
 * runs of the same GPU pipeline overlap.
 * </p>
 */
public final class NodeKindConcurrency {

	private NodeKindConcurrency() {
	}

	/**
	 * @param graph       the graph whose kinds are capped
	 * @param engine      the engine to configure, before it starts
	 * @param descriptors where the declared concurrency comes from; null or unknown kinds are left
	 *                    uncapped, because capping a kind nobody described would stall the run
	 *                    outright - a much worse failure than not capping it
	 */
	public static void apply(PipelineGraph graph, PipelineRunEngine engine, NodeDescriptorRegistry descriptors) {
		if (descriptors == null) {
			return;
		}
		for (PipelineGraphNode node : graph.getNodes()) {
			NodeDescriptor descriptor = descriptors.get(node.getKind());
			if (descriptor == null) {
				continue;
			}
			int max = descriptor.getDefaultConcurrency();
			if (max > 0) {
				engine.setMaxInFlightForKind(node.getKind(), max);
			}
		}
	}
}
