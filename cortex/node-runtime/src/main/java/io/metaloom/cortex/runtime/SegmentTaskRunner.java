package io.metaloom.cortex.runtime;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Runs a whole affinity segment locally, keeping intermediate results in memory.
 *
 * <p>This is {@link NodeTaskRunner} with N &gt; 1, which is what the Phase 1 runner
 * was shaped for. The saving is twofold: one round trip instead of one per node,
 * and — more importantly for video — the media handle is resolved once, so a
 * five-node pipeline opens and decodes the file once rather than five times.</p>
 *
 * <h2>Local semantics must match the engine's</h2>
 *
 * <p>Within a segment this applies the same rule the Loom engine applies between
 * segments: a failed dependency skips a dependent node <em>if that node is
 * blocking</em>, and a non-blocking node runs anyway and sees the failure in its
 * inputs. If the two disagreed, moving a node into an affinity group would silently
 * change what the pipeline does — the one thing an optimisation must never do.</p>
 *
 * <p>Every node is accounted for. A node skipped because an upstream one failed is
 * reported as {@code SKIPPED} rather than omitted; a missing result would leave the
 * engine waiting for a node nobody is going to run.</p>
 */
public class SegmentTaskRunner {

	private static final Logger log = LoggerFactory.getLogger(SegmentTaskRunner.class);

	private final NodeTaskRunner.NodeInstantiator instantiator;
	private final NodeTaskRunner.MediaResolver mediaResolver;

	public SegmentTaskRunner(NodeTaskRunner.NodeInstantiator instantiator,
		NodeTaskRunner.MediaResolver mediaResolver) {
		this.instantiator = instantiator;
		this.mediaResolver = mediaResolver;
	}

	/**
	 * Execute the segment.
	 *
	 * @param task the work
	 * @return one result per node, never null
	 */
	public SegmentTaskResult run(SegmentTask task) {
		LoomMedia media;
		try {
			// Resolved once for the whole segment. This is the actual win for video:
			// decode-once, analyse-many, instead of re-reading the file per node.
			media = mediaResolver.resolve(Paths.get(task.getMedia().getPath()));
		} catch (Exception e) {
			// Nothing can run, so report it at segment level rather than inventing an
			// identical failure for every node.
			log.error("Could not resolve media for {}", task, e);
			return new SegmentTaskResult(task.getTaskUuid(), task.getRunUuid(), task.getItemId(),
				task.getSegmentId(), List.of(), describe(e));
		}

		// Seeded with what came from outside the segment; nodes inside it add to this
		// as they go, and those additions never cross the network.
		Map<String, NodeResult> results = new LinkedHashMap<>(
			NodeResultMapper.toUpstreamResults(task.getUpstreamOutputs()));
		List<NodeTaskResult> wireResults = new ArrayList<>();

		for (SegmentNode node : task.getNodes()) {
			String skipReason = blockedBy(node, results);
			if (skipReason != null) {
				NodeTaskResult skipped = NodeTaskResult.skipped(node.getNodeId(), skipReason);
				wireResults.add(skipped);
				results.put(node.getNodeId(), NodeResult.skipped(node.getNodeId(), skipReason));
				continue;
			}

			NodeTaskResult result = runOne(task, node, media, results);
			wireResults.add(result);
			results.put(node.getNodeId(), NodeResultMapper.toLocal(result));
		}

		return new SegmentTaskResult(task.getTaskUuid(), task.getRunUuid(), task.getItemId(), task.getSegmentId(),
			wireResults, null);
	}

	/**
	 * @return why this node must be skipped, or null when it should run
	 */
	private String blockedBy(SegmentNode node, Map<String, NodeResult> results) {
		if (!node.isBlocking()) {
			// Runs anyway and sees the failure in its inputs, matching the engine.
			return null;
		}
		for (String dep : node.getDependencies()) {
			NodeResult depResult = results.get(dep);
			if (depResult != null && depResult.getState() == NodeState.FAILED) {
				return "Dependency " + dep + " failed";
			}
		}
		return null;
	}

	private NodeTaskResult runOne(SegmentTask task, SegmentNode node, LoomMedia media,
		Map<String, NodeResult> results) {
		long start = System.currentTimeMillis();
		try {
			PipelineNode instance = instantiator.create(toNodeDefinition(node));
			NodeResult result = instance.process(media, Map.copyOf(results));
			if (result == null) {
				return NodeTaskResult.failed(task.getTaskUuid(), node.getNodeId(),
					System.currentTimeMillis() - start,
					"Node '" + node.getNodeKind() + "' returned no result");
			}
			return NodeResultMapper.toWire(task.getTaskUuid(), result);
		} catch (Exception e) {
			// One bad node must not abandon the rest of the segment: the nodes after it
			// may not depend on it, and the engine needs an answer for every one.
			log.error("Node '{}' of {} failed", node.getNodeId(), task, e);
			return NodeTaskResult.failed(task.getTaskUuid(), node.getNodeId(),
				System.currentTimeMillis() - start, describe(e));
		}
	}

	/**
	 * Options are flattened to the top level, matching {@link NodeTaskRunner} - the
	 * existing node producers read them from there.
	 */
	private JsonObject toNodeDefinition(SegmentNode node) {
		JsonObject nodeDef = new JsonObject()
			.put("id", node.getNodeId())
			.put("type", node.getNodeKind());
		node.getOptions().forEach(nodeDef::put);
		return nodeDef;
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

}
