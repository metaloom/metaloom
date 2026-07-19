package io.metaloom.loom.pipeline.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, validated pipeline graph.
 *
 * <p>This is the Loom-side replacement for Cortex's {@code DefaultPipeline}. The
 * important difference is not the data structure but <em>who owns it</em>: under
 * Variant C the graph is held and walked here, and Cortex only ever sees one node
 * at a time.</p>
 *
 * <p>Build one with {@link PipelineGraphParser}. Construction fails rather than
 * silently degrading - a graph that cannot be executed as drawn is an error, not a
 * one-node pipeline.</p>
 */
public class PipelineGraph {

	/** Send each result as it is produced, unless the definition says otherwise. */
	public static final int DEFAULT_RESULT_BATCH_SIZE = 1;

	private final String name;
	private final boolean enabled;
	private final boolean dryRun;
	private final int priority;
	private int resultBatchSize = DEFAULT_RESULT_BATCH_SIZE;
	private final Map<String, PipelineGraphNode> nodes;
	private final Map<String, List<String>> children;
	private final List<String> topologicalOrder;
	private final String sourceNodeId;
	private volatile List<PipelineSegment> segments;

	PipelineGraph(String name, boolean enabled, boolean dryRun, int priority,
		Map<String, PipelineGraphNode> nodes, String sourceNodeId) {
		this.name = name;
		this.enabled = enabled;
		this.dryRun = dryRun;
		this.priority = priority;
		this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		this.sourceNodeId = sourceNodeId;

		Map<String, List<String>> childMap = new LinkedHashMap<>();
		for (String id : this.nodes.keySet()) {
			childMap.put(id, new ArrayList<>());
		}
		for (PipelineGraphNode node : this.nodes.values()) {
			for (String dep : node.getDependencies()) {
				childMap.get(dep).add(node.getId());
			}
		}
		Map<String, List<String>> immutableChildren = new LinkedHashMap<>();
		childMap.forEach((id, list) -> immutableChildren.put(id, List.copyOf(list)));
		this.children = Collections.unmodifiableMap(immutableChildren);

		this.topologicalOrder = topologicalSort();
	}

	public String getName() {
		return name;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * @return true when nodes should be skipped rather than executed
	 */
	public boolean isDryRun() {
		return dryRun;
	}

	/**
	 * How many node results a worker may accumulate before sending them together.
	 *
	 * <p>Taken verbatim from the pipeline definition. A cheap node over many small
	 * files produces a result per item per node, and at scale that is a lot of very
	 * small messages; batching trades a little latency for far fewer of them.</p>
	 *
	 * <p>1 means send each result as it happens, which is the previous behaviour and
	 * the default — batching is something a pipeline opts into.</p>
	 *
	 * @return the batch size, never below 1
	 */
	public int getResultBatchSize() {
		return resultBatchSize;
	}

	void setResultBatchSize(int resultBatchSize) {
		this.resultBatchSize = Math.max(1, resultBatchSize);
	}

	public int getPriority() {
		return priority;
	}

	/**
	 * @return the single source node id
	 */
	public String getSourceNodeId() {
		return sourceNodeId;
	}

	public PipelineGraphNode getSourceNode() {
		return nodes.get(sourceNodeId);
	}

	/**
	 * @param id node id
	 * @return the node, or null when absent
	 */
	public PipelineGraphNode getNode(String id) {
		return nodes.get(id);
	}

	/** @return all nodes, in definition order */
	public java.util.Collection<PipelineGraphNode> getNodes() {
		return nodes.values();
	}

	public int size() {
		return nodes.size();
	}

	/**
	 * @param id node id
	 * @return ids of the nodes that depend on the given node
	 */
	public List<String> getChildren(String id) {
		return children.getOrDefault(id, List.of());
	}

	/**
	 * @return every node id in dependency order - a node always appears after all of
	 *         its dependencies
	 */
	/**
	 * The affinity segments of this graph.
	 *
	 * <p>Computed once and cached: the topology belongs to the pipeline version, not
	 * to an item, and re-deriving it per item would put graph analysis on the hot
	 * path of every dispatch.</p>
	 *
	 * @return segments in dependency order
	 */
	public List<PipelineSegment> getSegments() {
		List<PipelineSegment> local = segments;
		if (local == null) {
			// Idempotent, so a benign race just recomputes rather than needing a lock on
			// the read path.
			local = new PipelineSegmenter().segment(this);
			segments = local;
		}
		return local;
	}

	/**
	 * @param nodeId the node
	 * @return the segment it belongs to, or null for the source node
	 */
	public PipelineSegment getSegmentFor(String nodeId) {
		for (PipelineSegment segment : getSegments()) {
			if (segment.getNodeIds().contains(nodeId)) {
				return segment;
			}
		}
		return null;
	}

	public List<String> getTopologicalOrder() {
		return topologicalOrder;
	}

	/**
	 * Kahn's algorithm. Doubles as the cycle check: a graph with a cycle cannot be
	 * fully ordered, and we fail loudly rather than execute a partial graph.
	 */
	private List<String> topologicalSort() {
		Map<String, Integer> inDegree = new HashMap<>();
		for (PipelineGraphNode node : nodes.values()) {
			inDegree.put(node.getId(), node.getDependencies().size());
		}

		Deque<String> ready = new ArrayDeque<>();
		// Seed in definition order so the result is deterministic across runs.
		for (String id : nodes.keySet()) {
			if (inDegree.get(id) == 0) {
				ready.add(id);
			}
		}

		List<String> ordered = new ArrayList<>(nodes.size());
		while (!ready.isEmpty()) {
			String id = ready.poll();
			ordered.add(id);
			for (String child : getChildren(id)) {
				int remaining = inDegree.get(child) - 1;
				inDegree.put(child, remaining);
				if (remaining == 0) {
					ready.add(child);
				}
			}
		}

		if (ordered.size() != nodes.size()) {
			List<String> cyclic = new ArrayList<>(nodes.keySet());
			cyclic.removeAll(ordered);
			throw new GraphValidationException(
				"Pipeline '" + name + "' has a dependency cycle involving: " + cyclic);
		}
		return List.copyOf(ordered);
	}

	@Override
	public String toString() {
		return "PipelineGraph[" + name + ", " + nodes.size() + " nodes, source=" + sourceNodeId + "]";
	}
}
