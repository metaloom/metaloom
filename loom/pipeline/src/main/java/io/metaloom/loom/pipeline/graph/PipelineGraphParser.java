package io.metaloom.loom.pipeline.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.model.FilterBranch;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Parses a stored Loom pipeline definition into an executable {@link PipelineGraph}.
 *
 * <p><strong>This class is the fix for the defect that broke the feature end to
 * end.</strong> Loom stores a graph as {@code nodes[]} plus a top-level
 * {@code edges[]} array. The Cortex-side loader read {@code nodes[].dependencies[]}
 * and never looked at {@code edges}, so every UI-authored pipeline arrived as a set
 * of disconnected nodes and collapsed to just its source. Under Variant C there is
 * only one parser - this one - so the two formats cannot drift apart again.</p>
 *
 * <p>Expected shape:</p>
 *
 * <pre>
 * {
 *   "nodes": [ {"id": "pn1", "type": "filesystem-source", "name": "Source"},
 *              {"id": "pn2", "type": "sha512", "blocking": true, "syncToLoom": true,
 *               "options": {"algorithm": "sha512"}} ],
 *   "edges": [ {"id": "pe1", "source": "pn1", "target": "pn2", "branch": "PASS"} ]
 * }
 * </pre>
 *
 * <p>{@code branch} is optional and defaults to {@link FilterBranch#ANY}. It is what
 * makes PASS/REJECT filter routing expressible from the UI at all - the old Loom
 * format had no way to say it.</p>
 *
 * <p>{@code nodes[].dependencies[]} is still honoured as a fallback so that
 * definitions written in the older Cortex serde shape continue to load. When both
 * are present, {@code edges} wins.</p>
 */
public class PipelineGraphParser {

	private final NodeDescriptorRegistry registry;

	/**
	 * @param registry used to resolve which kinds exist and which are sources; may be
	 *                 null to skip descriptor-based checks (useful in tests)
	 */
	public PipelineGraphParser(NodeDescriptorRegistry registry) {
		this.registry = registry;
	}

	public PipelineGraphParser() {
		this(null);
	}

	/**
	 * @param name       pipeline name, used in error messages
	 * @param definition the stored definition JSON
	 * @param enabled    whether the pipeline is enabled
	 * @param dryRun     whether nodes should be skipped rather than executed
	 * @param priority   dispatch priority
	 * @return the parsed graph
	 * @throws GraphValidationException when the definition cannot be executed as drawn
	 */
	public PipelineGraph parse(String name, JsonObject definition, boolean enabled, boolean dryRun, int priority) {
		if (definition == null) {
			throw new GraphValidationException("Pipeline '" + name + "' has no definition");
		}

		JsonArray nodeArray = definition.getJsonArray("nodes");
		if (nodeArray == null || nodeArray.isEmpty()) {
			throw new GraphValidationException("Pipeline '" + name + "' contains no nodes");
		}

		// Pass 1: read the node declarations.
		Map<String, JsonObject> raw = new LinkedHashMap<>();
		for (int i = 0; i < nodeArray.size(); i++) {
			JsonObject node = nodeArray.getJsonObject(i);
			if (node == null) {
				throw new GraphValidationException("Pipeline '" + name + "' has a null node at index " + i);
			}
			String id = node.getString("id");
			if (id == null || id.isBlank()) {
				throw new GraphValidationException("Pipeline '" + name + "' has a node without an id at index " + i);
			}
			if (raw.put(id, node) != null) {
				throw new GraphValidationException("Pipeline '" + name + "' has a duplicate node id: '" + id + "'");
			}
		}

		// Pass 2: resolve edges into per-node dependencies.
		Map<String, List<String>> dependencies = new LinkedHashMap<>();
		Map<String, Map<String, FilterBranch>> conditional = new LinkedHashMap<>();
		for (String id : raw.keySet()) {
			dependencies.put(id, new ArrayList<>());
			conditional.put(id, new LinkedHashMap<>());
		}

		JsonArray edges = definition.getJsonArray("edges");
		boolean usedEdges = edges != null && !edges.isEmpty();
		if (usedEdges) {
			applyEdges(name, edges, raw.keySet(), dependencies, conditional);
		} else {
			applyInlineDependencies(name, raw, dependencies, conditional);
		}

		// Pass 3: build the nodes.
		Map<String, PipelineGraphNode> nodes = new LinkedHashMap<>();
		for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
			String id = entry.getKey();
			JsonObject node = entry.getValue();

			String kind = node.getString("type", node.getString("kind"));
			if (kind == null || kind.isBlank()) {
				throw new GraphValidationException(
					"Pipeline '" + name + "' node '" + id + "' has no type");
			}
			if (registry != null && !registry.contains(kind)) {
				throw new GraphValidationException("Pipeline '" + name + "' node '" + id
					+ "' has unknown type '" + kind + "'");
			}

			boolean source = node.getBoolean("source", isSourceKind(kind));
			// Default true: a failed dependency should stop downstream work unless the
			// author explicitly opts out. Failing open would hide errors.
			boolean blocking = node.getBoolean("blocking", true);
			boolean syncToLoom = node.getBoolean("syncToLoom", false);

			JsonObject options = node.getJsonObject("options");
			nodes.put(id, new PipelineGraphNode(id, kind, node.getString("name"), source, blocking, syncToLoom,
				options == null ? Map.of() : options.getMap(),
				dependencies.get(id), conditional.get(id)));
		}

		String sourceId = resolveSourceNode(name, nodes, dependencies);
		return new PipelineGraph(name, enabled, dryRun, priority, nodes, sourceId);
	}

	private void applyEdges(String name, JsonArray edges, Set<String> nodeIds,
		Map<String, List<String>> dependencies, Map<String, Map<String, FilterBranch>> conditional) {

		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			if (edge == null) {
				throw new GraphValidationException("Pipeline '" + name + "' has a null edge at index " + i);
			}
			String from = edge.getString("source");
			String to = edge.getString("target");
			if (from == null || from.isBlank() || to == null || to.isBlank()) {
				throw new GraphValidationException(
					"Pipeline '" + name + "' has an edge without source/target at index " + i);
			}
			if (!nodeIds.contains(from)) {
				throw new GraphValidationException("Pipeline '" + name + "' edge at index " + i
					+ " references unknown source node '" + from + "'");
			}
			if (!nodeIds.contains(to)) {
				throw new GraphValidationException("Pipeline '" + name + "' edge at index " + i
					+ " references unknown target node '" + to + "'");
			}
			if (!seen.add(from + "->" + to)) {
				// A duplicate edge would make the node wait for the same dependency twice.
				continue;
			}
			dependencies.get(to).add(from);

			String branch = edge.getString("branch");
			if (branch != null && !branch.isBlank()) {
				FilterBranch parsed;
				try {
					parsed = FilterBranch.valueOf(branch.toUpperCase());
				} catch (IllegalArgumentException e) {
					throw new GraphValidationException("Pipeline '" + name + "' edge " + from + "->" + to
						+ " has unknown branch '" + branch + "'; expected ANY, PASS or REJECT");
				}
				if (parsed != FilterBranch.ANY) {
					conditional.get(to).put(from, parsed);
				}
			}
		}
	}

	/**
	 * Fallback for definitions written in the older Cortex serde shape, where each node
	 * carries its own {@code dependencies[]} and {@code conditionalDependencies{}}.
	 */
	private void applyInlineDependencies(String name, Map<String, JsonObject> raw,
		Map<String, List<String>> dependencies, Map<String, Map<String, FilterBranch>> conditional) {

		for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
			String id = entry.getKey();
			JsonArray deps = entry.getValue().getJsonArray("dependencies");
			if (deps != null) {
				for (int i = 0; i < deps.size(); i++) {
					String dep = deps.getString(i);
					if (dep == null || dep.isBlank()) {
						continue;
					}
					if (!raw.containsKey(dep)) {
						throw new GraphValidationException("Pipeline '" + name + "' node '" + id
							+ "' depends on unknown node '" + dep + "'");
					}
					dependencies.get(id).add(dep);
				}
			}
			JsonObject conditionalDeps = entry.getValue().getJsonObject("conditionalDependencies");
			if (conditionalDeps != null) {
				for (String dep : conditionalDeps.fieldNames()) {
					if (!raw.containsKey(dep)) {
						throw new GraphValidationException("Pipeline '" + name + "' node '" + id
							+ "' has a conditional dependency on unknown node '" + dep + "'");
					}
					String branch = conditionalDeps.getString(dep);
					FilterBranch parsed = FilterBranch.valueOf(branch.toUpperCase());
					if (!dependencies.get(id).contains(dep)) {
						dependencies.get(id).add(dep);
					}
					if (parsed != FilterBranch.ANY) {
						conditional.get(id).put(dep, parsed);
					}
				}
			}
		}
	}

	private boolean isSourceKind(String kind) {
		if (registry == null) {
			return false;
		}
		NodeDescriptor descriptor = registry.get(kind);
		return descriptor != null && descriptor.getCategory() == NodeCategory.SOURCE;
	}

	/**
	 * A pipeline has exactly one source. Ambiguity is an error rather than a guess -
	 * silently picking the first dependency-free node is how the previous loader
	 * turned a broken graph into a plausible-looking one-node run.
	 */
	private String resolveSourceNode(String name, Map<String, PipelineGraphNode> nodes,
		Map<String, List<String>> dependencies) {

		List<String> declared = nodes.values().stream()
			.filter(PipelineGraphNode::isSource)
			.map(PipelineGraphNode::getId)
			.toList();

		if (declared.size() == 1) {
			return declared.get(0);
		}
		if (declared.size() > 1) {
			throw new GraphValidationException("Pipeline '" + name + "' declares more than one source node: "
				+ declared + "; exactly one is required");
		}

		List<String> roots = dependencies.entrySet().stream()
			.filter(e -> e.getValue().isEmpty())
			.map(Map.Entry::getKey)
			.toList();

		if (roots.size() == 1) {
			return roots.get(0);
		}
		if (roots.isEmpty()) {
			throw new GraphValidationException("Pipeline '" + name
				+ "' has no source node and no node without dependencies");
		}
		throw new GraphValidationException("Pipeline '" + name + "' has no declared source node and "
			+ roots.size() + " candidates without dependencies: " + roots
			+ "; mark one node with \"source\": true");
	}
}
