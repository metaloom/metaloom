package io.metaloom.cortex.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Executes a single {@link NodeTask} and reports the outcome.
 *
 * <p>This is what remains of the in-Cortex DAG executor once Loom owns the graph.
 * It answers exactly one question - "run this node on this file" - and knows
 * nothing about dependencies, ordering, filters or run state.</p>
 *
 * <p><strong>Why it runs a set rather than a single node.</strong> Today every task
 * carries one node. Affinity groups will later let Loom dispatch a whole subgraph
 * so that intermediate results stay in this process instead of crossing the network
 * per edge. Keeping the shape "N nodes over one media item" from the start means
 * that becomes a change of N, not a rewrite - and it is why the old executor was
 * reduced rather than deleted.</p>
 *
 * <p>A node that throws is converted into a {@code FAILED} result rather than
 * propagating: one bad file must not take down the worker, and the engine needs an
 * answer for every task it dispatched.</p>
 */
public class NodeTaskRunner {

	private static final Logger log = LoggerFactory.getLogger(NodeTaskRunner.class);

	private final NodeInstantiator instantiator;
	private final MediaResolver mediaResolver;

	/**
	 * Resolves a node definition to an executable node.
	 *
	 * <p>Deliberately narrow so the runner can be tested without Dagger, the node
	 * registry, or any concrete node implementation.</p>
	 */
	@FunctionalInterface
	public interface NodeInstantiator {

		/**
		 * @param nodeDef definition carrying at least {@code id} and {@code type}
		 * @return the node
		 * @throws RuntimeException when the kind is not executable on this worker
		 */
		PipelineNode create(JsonObject nodeDef);
	}

	/** Turns a path into the media handle the node expects. */
	@FunctionalInterface
	public interface MediaResolver {
		LoomMedia resolve(Path path);
	}

	public NodeTaskRunner(NodeInstantiator instantiator, MediaResolver mediaResolver) {
		this.instantiator = instantiator;
		this.mediaResolver = mediaResolver;
	}

	/**
	 * Run the task.
	 *
	 * @param task the work
	 * @return the outcome, never null - every dispatched task gets an answer
	 */
	public NodeTaskResult run(NodeTask task) {
		long start = System.currentTimeMillis();
		try {
			PipelineNode node = instantiator.create(toNodeDefinition(task));
			LoomMedia media = mediaResolver.resolve(Paths.get(task.getMedia().getPath()));
			Map<String, NodeResult> upstream = NodeResultMapper.toUpstreamResults(task.getUpstreamOutputs());

			NodeResult result = node.process(media, upstream);
			if (result == null) {
				// A node returning null is a bug in that node, but the engine still
				// needs a definite answer for this task.
				return NodeTaskResult.failed(task.getTaskUuid(), task.getNodeId(),
					System.currentTimeMillis() - start,
					"Node '" + task.getNodeKind() + "' returned no result");
			}
			return NodeResultMapper.toWire(task.getTaskUuid(), result);
		} catch (Exception e) {
			log.error("Node task {} failed", task, e);
			return NodeTaskResult.failed(task.getTaskUuid(), task.getNodeId(),
				System.currentTimeMillis() - start, describe(e));
		}
	}

	/**
	 * Rebuild the JSON node definition the node factory expects.
	 *
	 * <p>Options are flattened to the top level because that is where the existing
	 * node producers read them from - {@code filesystem-source} reads {@code path}
	 * and {@code pathGlobs} directly off the definition, for instance. Keeping that
	 * contract means the factory and every producer are reused unchanged.</p>
	 */
	private JsonObject toNodeDefinition(NodeTask task) {
		JsonObject nodeDef = new JsonObject()
			.put("id", task.getNodeId())
			.put("type", task.getNodeKind());
		if (task.getOptions() != null) {
			task.getOptions().forEach(nodeDef::put);
		}
		return nodeDef;
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}
}
