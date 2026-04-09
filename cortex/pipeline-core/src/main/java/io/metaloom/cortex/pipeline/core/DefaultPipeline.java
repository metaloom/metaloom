package io.metaloom.cortex.pipeline.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * Default implementation of {@link Pipeline}.
 * Nodes are maintained in topological order based on their connections.
 *
 * <p>The pipeline takes a single source node and discovers all reachable nodes
 * by walking the connection graph.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * sourceNode.connectTo(filterNode);
 * filterNode.connectTo(hashNode, FilterBranch.PASS);
 *
 * Pipeline pipeline = DefaultPipeline.builder("my-pipeline")
 *     .source(sourceNode)
 *     .build();
 * </pre>
 */
public class DefaultPipeline implements Pipeline {

	private final String name;
	private final String description;
	private final int priority;
	private final boolean enabled;
	private final boolean dryRun;
	private final PipelineNode sourceNode;
	private final List<PipelineNode> nodes;
	private final Map<String, PipelineNode> nodeIndex;

	private DefaultPipeline(Builder builder) {
		this.name = builder.name;
		this.description = builder.description;
		this.priority = builder.priority;
		this.enabled = builder.enabled;
		this.dryRun = builder.dryRun;

		if (builder.sourceNode == null) {
			throw new IllegalStateException("Pipeline '" + name + "' must have a source node set via .source()");
		}
		this.sourceNode = builder.sourceNode;

		// Discover all nodes by walking the graph from the source
		List<PipelineNode> discovered = discoverNodes(this.sourceNode);

		this.nodeIndex = new LinkedHashMap<>();
		for (PipelineNode node : discovered) {
			nodeIndex.put(node.id(), node);
		}
		this.nodes = Collections.unmodifiableList(topologicalSort(discovered));
	}

	/**
	 * Walk the connection graph starting from the source node and collect all reachable nodes.
	 */
	private List<PipelineNode> discoverNodes(PipelineNode source) {
		Set<String> visited = new LinkedHashSet<>();
		Queue<PipelineNode> queue = new LinkedList<>();
		List<PipelineNode> result = new ArrayList<>();

		queue.add(source);
		visited.add(source.id());

		while (!queue.isEmpty()) {
			PipelineNode current = queue.poll();
			result.add(current);
			for (PipelineNode child : current.children()) {
				if (visited.add(child.id())) {
					queue.add(child);
				}
			}
		}
		return result;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public int priority() {
		return priority;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public boolean isDryRun() {
		return dryRun;
	}

	@Override
	public PipelineNode sourceNode() {
		return sourceNode;
	}

	@Override
	public List<PipelineNode> nodes() {
		return nodes;
	}

	@Override
	public PipelineNode node(String nodeId) {
		return nodeIndex.get(nodeId);
	}

	/**
	 * Topological sort using Kahn's algorithm.
	 * Ensures nodes execute only after their dependencies.
	 */
	private List<PipelineNode> topologicalSort(List<PipelineNode> inputNodes) {
		Map<String, PipelineNode> lookup = new LinkedHashMap<>();
		Map<String, Set<String>> incomingEdges = new HashMap<>();
		Map<String, Set<String>> outgoingEdges = new HashMap<>();

		for (PipelineNode node : inputNodes) {
			lookup.put(node.id(), node);
			incomingEdges.put(node.id(), new HashSet<>(node.dependencies()));
			outgoingEdges.computeIfAbsent(node.id(), k -> new HashSet<>());
			for (String dep : node.dependencies()) {
				outgoingEdges.computeIfAbsent(dep, k -> new HashSet<>()).add(node.id());
			}
		}

		List<PipelineNode> sorted = new ArrayList<>();
		List<String> ready = new ArrayList<>();

		for (Map.Entry<String, Set<String>> entry : incomingEdges.entrySet()) {
			// Only consider dependencies that are actually nodes in this pipeline
			entry.getValue().retainAll(lookup.keySet());
			if (entry.getValue().isEmpty()) {
				ready.add(entry.getKey());
			}
		}

		while (!ready.isEmpty()) {
			String nodeId = ready.remove(0);
			PipelineNode node = lookup.get(nodeId);
			if (node != null) {
				sorted.add(node);
			}
			Set<String> downstream = outgoingEdges.getOrDefault(nodeId, Collections.emptySet());
			for (String downId : downstream) {
				Set<String> deps = incomingEdges.get(downId);
				if (deps != null) {
					deps.remove(nodeId);
					if (deps.isEmpty()) {
						ready.add(downId);
					}
				}
			}
		}

		if (sorted.size() != inputNodes.size()) {
			throw new IllegalStateException(
					"Pipeline '" + name + "' has a dependency cycle. Sorted " + sorted.size() + " of "
							+ inputNodes.size() + " nodes.");
		}

		return sorted;
	}

	@Override
	public String toString() {
		return "Pipeline{name=" + name + ", priority=" + priority + ", nodes=" + nodes.size()
				+ (dryRun ? " [DRY-RUN]" : "") + (!enabled ? " [DISABLED]" : "") + "}";
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static class Builder {
		private final String name;
		private String description = "";
		private int priority = 0;
		private boolean enabled = true;
		private boolean dryRun = false;
		private PipelineNode sourceNode;

		private Builder(String name) {
			this.name = name;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder priority(int priority) {
			this.priority = priority;
			return this;
		}

		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		public Builder dryRun(boolean dryRun) {
			this.dryRun = dryRun;
			return this;
		}

		/**
		 * Set the source node of the pipeline. All other nodes are discovered by
		 * walking the connection graph from this node.
		 *
		 * @param source the source node
		 * @return this builder
		 */
		public Builder source(PipelineNode source) {
			this.sourceNode = source;
			return this;
		}

		public DefaultPipeline build() {
			return new DefaultPipeline(this);
		}
	}
}
