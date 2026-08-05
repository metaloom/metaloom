package io.metaloom.cortex.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.impl.ScopedArtifactCache;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Runs a whole affinity segment locally, keeping intermediate results in memory.
 *
 * <p>This is {@link NodeTaskRunner} with N &gt; 1, which is what the Phase 1 runner
 * was shaped for. The saving is <strong>one round trip instead of N</strong>, plus
 * one dispatch decision instead of N.</p>
 *
 * <h2>Decode once</h2>
 *
 * <p>Dispatching a segment is not by itself a decode-once saving: the media handle is
 * resolved once, but {@link LoomMedia} is a lightweight file reference rather than a
 * decoded artifact, so a node that needs frames still reads the file itself. A benchmark
 * over 155 MiB of real video measured segment dispatch at 1.01× per-node dispatch —
 * within noise, because the round trips it saves were never the expensive part.</p>
 *
 * <p>What makes decode-once possible is the {@link ArtifactCache} this opens for the
 * segment. Node outputs travel — they are serialised back to Loom — and are therefore
 * the wrong home for a frame buffer; the artifact scope stays here and is the right one.
 * A node publishes into it, a later node in the same segment reads from it, and the
 * whole thing is closed when the segment ends. The saving is real only for the nodes
 * that opt in: the mechanism is here, adopting it is per node.</p>
 *
 * <p>One scope per {@code run()}, so item B never sees item A's artifacts, and a retry
 * after a lease expiry starts from nothing rather than inheriting whatever the failed
 * attempt had built. Each node runs inside its own publication window, so an artifact
 * published by a node that then fails is discarded instead of being served — half-built
 * to the eye of the type system is indistinguishable from finished.</p>
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
	private final long maxArtifactBytes;

	public SegmentTaskRunner(NodeTaskRunner.NodeInstantiator instantiator,
		NodeTaskRunner.MediaResolver mediaResolver) {
		this(instantiator, mediaResolver, ScopedArtifactCache.DEFAULT_MAX_BYTES);
	}

	/**
	 * @param maxArtifactBytes
	 *            the ceiling for one segment's artifact scope. Bounds a single segment; the
	 *            across-a-long-run bound is the scope's lifetime, not this number.
	 */
	public SegmentTaskRunner(NodeTaskRunner.NodeInstantiator instantiator,
		NodeTaskRunner.MediaResolver mediaResolver, long maxArtifactBytes) {
		this.instantiator = instantiator;
		this.mediaResolver = mediaResolver;
		this.maxArtifactBytes = maxArtifactBytes;
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
			media = mediaResolver.resolve(task.getMedia());
		} catch (Exception e) {
			// Nothing can run, so report it at segment level rather than inventing an
			// identical failure for every node.
			log.error("Could not resolve media for {}", task, e);
			return new SegmentTaskResult(task.getTaskUuid(), task.getRunUuid(), task.getItemId(),
				task.getSegmentId(), List.of(), describe(e));
		}

		// What came from outside the segment. Every member sees all of it; the engine already
		// narrowed it to ports that genuinely arrive from elsewhere.
		Map<String, PortPayload> external = new LinkedHashMap<>(task.getInputs());
		// What each member produced, kept per node rather than merged into one pool. A member
		// reads a fellow member's output only by declaring it as a dependency - see visibleInputs.
		Map<String, Map<String, PortPayload>> produced = new LinkedHashMap<>();
		Map<String, NodeState> states = new LinkedHashMap<>();
		List<NodeTaskResult> wireResults = new ArrayList<>();

		// One scope per run, closed on the way out however the segment ends. That is what
		// makes "item B cannot see item A's artifact" structural rather than a rule someone
		// has to remember, and what releases native-backed artifacts deterministically.
		try (ScopedArtifactCache artifacts = new ScopedArtifactCache(task.getItemId(), maxArtifactBytes)) {
			for (SegmentNode node : task.getNodes()) {
				String skipReason = blockedBy(node, states);
				if (skipReason != null) {
					NodeTaskResult skipped = NodeTaskResult.skipped(node.getNodeId(), skipReason);
					wireResults.add(skipped);
					states.put(node.getNodeId(), NodeState.SKIPPED);
					continue;
				}

				NodeTaskResult result = runOne(task, node, media, visibleInputs(node, external, produced), artifacts);
				wireResults.add(result);
				states.put(node.getNodeId(), result.getState());
				produced.put(node.getNodeId(), result.getOutputs());
			}
		}

		return new SegmentTaskResult(task.getTaskUuid(), task.getRunUuid(), task.getItemId(), task.getSegmentId(),
			wireResults, null);
	}

	/**
	 * What one member of the segment is allowed to see: everything from outside the segment, plus
	 * the outputs of the members it <em>declares</em> as dependencies.
	 *
	 * <p>
	 * Merging every member's outputs into one pool instead would make being in a segment a source
	 * of data. Affinity groups exist to fuse independent analysers of the same media, and those
	 * routinely share port names — {@code consistency} emits {@code is_complete} and
	 * {@code thumbnail} declares one — so a node would pick up a value it has no edge to and
	 * compute something different purely because of a scheduling hint. Dependencies are on the
	 * wire precisely so the worker can tell an edge from a coincidence.
	 * </p>
	 *
	 * <p>
	 * Port ids still do the matching within a declared edge, so an edge whose two ends are named
	 * differently is not carried locally - the limitation the segment wire model already had, now
	 * confined to edges that genuinely exist.
	 * </p>
	 */
	private Map<String, PortPayload> visibleInputs(SegmentNode node, Map<String, PortPayload> external,
		Map<String, Map<String, PortPayload>> produced) {
		Map<String, PortPayload> visible = new LinkedHashMap<>(external);
		for (String dependency : node.getDependencies()) {
			Map<String, PortPayload> outputs = produced.get(dependency);
			if (outputs != null) {
				visible.putAll(outputs);
			}
		}
		return visible;
	}

	/**
	 * @return why this node must be skipped, or null when it should run
	 */
	private String blockedBy(SegmentNode node, Map<String, NodeState> states) {
		if (!node.isBlocking()) {
			// Runs anyway and sees the failure in its inputs, matching the engine.
			return null;
		}
		for (String dep : node.getDependencies()) {
			if (states.get(dep) == NodeState.FAILED) {
				return "Dependency " + dep + " failed";
			}
		}
		return null;
	}

	private NodeTaskResult runOne(SegmentTask task, SegmentNode node, LoomMedia media,
		Map<String, PortPayload> available, ArtifactCache artifacts) {
		long start = System.currentTimeMillis();
		// Everything this node publishes is provisional until it succeeds. Closing the
		// window without committing takes it back out - see ArtifactCache on why the
		// conservative direction is the right one here.
		try (ArtifactCache.Publication publication = artifacts.beginPublication()) {
			PipelineNode instance = instantiator.create(toNodeDefinition(node));
			NodeResult result = instance.process(media, NodeResultMapper.toInputs(task, available, artifacts));
			if (result == null) {
				return NodeTaskResult.failed(task.getTaskUuid(), node.getNodeId(),
					System.currentTimeMillis() - start,
					"Node '" + node.getNodeKind() + "' returned no result");
			}
			NodeTaskResult wire = NodeResultMapper.toWire(task, result);
			if (wire.getState() != NodeState.FAILED) {
				publication.commit();
			}
			return wire;
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
