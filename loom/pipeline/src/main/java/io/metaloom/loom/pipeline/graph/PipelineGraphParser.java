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
 * <p>{@code nodes[].dependencies[]} is <strong>no longer read</strong>. It cannot say which ports an
 * edge joins, and a graph whose connections carry no ports cannot be type-checked - so accepting it
 * would mean a definition that saves without validation and fails at run time instead.</p>
 *
 * <h2>Format version</h2>
 *
 * <p>A definition carries a top-level {@code "version"} integer naming the format it was written
 * in. This parser accepts up to {@link #CURRENT_DEFINITION_VERSION} and refuses anything higher,
 * by name, rather than half-reading a shape it does not know: a definition written by a newer Loom
 * may use fields whose absence here would look like a valid but different graph.</p>
 *
 * <p>An <em>absent</em> version means version 1. Every definition stored before the field existed
 * is a version 1 definition, and rejecting them would strand pipelines that run correctly.
 * {@link #stampVersion(JsonObject)} adds the field on write so the untagged set only shrinks.</p>
 */
public class PipelineGraphParser {

	/**
	 * The definition format this Loom writes and is the highest it can read.
	 *
	 * <p>Bump when a change to the format cannot be understood by an older reader — a renamed or
	 * removed field, or a changed meaning for an existing one. Purely additive optional fields
	 * ({@code syncToLoom}, {@code affinity}, {@code options} and the filter {@code branch} were all
	 * added this way) do not need a bump: an older reader ignoring them still executes the graph as
	 * drawn.</p>
	 */
	public static final int CURRENT_DEFINITION_VERSION = 1;

	/** Top-level key naming the format version. */
	public static final String VERSION_KEY = "version";

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
	 * Read and check the format version of a definition.
	 *
	 * @param name       pipeline name, used in error messages
	 * @param definition the stored definition JSON
	 * @return the declared version, or {@link #CURRENT_DEFINITION_VERSION} when the field is absent
	 * @throws GraphValidationException if the version is malformed, or newer than this Loom reads
	 */
	public static int readVersion(String name, JsonObject definition) {
		Object raw = definition.getValue(VERSION_KEY);
		if (raw == null) {
			// Written before the field existed, which by definition makes it version 1.
			return 1;
		}
		if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
			throw new GraphValidationException("Pipeline '" + name + "' declares a non-integer definition "
				+ VERSION_KEY + ": " + raw);
		}
		int version = number.intValue();
		if (version < 1) {
			throw new GraphValidationException("Pipeline '" + name + "' declares definition " + VERSION_KEY
				+ " " + version + "; versions start at 1");
		}
		if (version > CURRENT_DEFINITION_VERSION) {
			throw new GraphValidationException("Pipeline '" + name + "' is in definition format version "
				+ version + ", but this Loom reads up to version " + CURRENT_DEFINITION_VERSION
				+ ". Upgrade Loom, or edit the pipeline with the version that wrote it");
		}
		return version;
	}

	/**
	 * Return the definition with its format version set, so a stored definition always names the
	 * format it is in.
	 *
	 * <p>Applied on every write. An existing {@code version} is left alone — re-stamping would
	 * silently relabel a definition as current after an older Loom round-tripped it.</p>
	 *
	 * @param definition the definition to stamp; {@code null} is returned unchanged
	 * @return the same instance, for chaining
	 */
	public static JsonObject stampVersion(JsonObject definition) {
		if (definition != null && definition.getValue(VERSION_KEY) == null) {
			definition.put(VERSION_KEY, CURRENT_DEFINITION_VERSION);
		}
		return definition;
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

		// Checked before anything else is read: if the format is one this Loom does not know,
		// every message that follows would be about the wrong thing.
		readVersion(name, definition);

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

		// Pass 2: resolve edges into per-node dependencies and port bindings.
		Map<String, List<String>> dependencies = new LinkedHashMap<>();
		Map<String, Map<String, FilterBranch>> conditional = new LinkedHashMap<>();
		Map<String, List<InputBinding>> bindings = new LinkedHashMap<>();
		for (String id : raw.keySet()) {
			dependencies.put(id, new ArrayList<>());
			conditional.put(id, new LinkedHashMap<>());
			bindings.put(id, new ArrayList<>());
		}

		JsonArray edges = definition.getJsonArray("edges");
		// The older Cortex serde shape carried connections as nodes[].dependencies[]. It cannot say
		// which ports an edge joins, so such a definition would parse into a graph with no bindings
		// at all and then fail the required-input check with a message about the wrong thing.
		// Rejecting it where it is recognised says what is actually wrong. A definition with no
		// edges is *not* itself an error - a single-node pipeline is legitimate.
		for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
			if (entry.getValue().containsKey("dependencies")) {
				throw new GraphValidationException("Pipeline '" + name + "' node '" + entry.getKey()
					+ "' declares nodes[].dependencies[], which is no longer read."
					+ " Connections are port-to-port now: use edges[] with source/sourcePort and"
					+ " target/targetPort");
			}
		}
		if (edges != null && !edges.isEmpty()) {
			applyEdges(name, edges, raw.keySet(), dependencies, conditional, bindings);
		}

		// Which output ports have an outgoing edge — handed to the worker so a node can skip
		// producing what nobody asked for.
		Map<String, Set<String>> demanded = new LinkedHashMap<>();
		for (List<InputBinding> nodeBindings : bindings.values()) {
			for (InputBinding binding : nodeBindings) {
				demanded.computeIfAbsent(binding.sourceNodeId(), k -> new LinkedHashSet<>())
					.add(binding.sourcePortId());
			}
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

			JsonObject options = readOptions(node);
			nodes.put(id, new PipelineGraphNode(id, kind, node.getString("name"), source, blocking, syncToLoom,
				node.getString("affinity"),
				options == null ? Map.of() : options.getMap(),
				dependencies.get(id), conditional.get(id),
				bindings.get(id), demanded.getOrDefault(id, Set.of())));
		}

		String sourceId = resolveSourceNode(name, nodes, dependencies);
		PipelineGraph graph = new PipelineGraph(name, enabled, dryRun, priority, nodes, sourceId);
		// Port checking and fan-out classification need the whole graph, and the topological order
		// the graph just computed - effective multiplicity only propagates in dependency order.
		new PortGraphAnalyzer(registry).analyze(name, nodes, graph.getTopologicalOrder());
		// Pipeline-wide rather than per-node: a worker accumulates whatever it has
		// finished for a run, and which node produced it does not change the cost of
		// the message.
		graph.setResultBatchSize(definition.getInteger("resultBatchSize", PipelineGraph.DEFAULT_RESULT_BATCH_SIZE));
		graph.setReuseResults(definition.getBoolean("reuseResults", false));
		return graph;
	}

	/**
	 * Read a node's per-instance options.
	 *
	 * <p>
	 * {@code options} is the documented shape. {@code config} is accepted as a legacy alias
	 * because the pipeline editor used to serialise node parameters under that name, which meant
	 * they were silently dropped here and never reached a worker. Definitions saved before that
	 * fix still carry {@code config}, so they must keep loading. When both are present
	 * {@code options} wins - it is the shape the editor writes now.
	 * </p>
	 *
	 * <p>
	 * <strong>The re-encode is load-bearing.</strong> {@code JsonObject.getMap()} hands back the
	 * backing map with its values exactly as stored, so a definition <em>parsed from a string</em>
	 * yields plain {@code Map}s and {@code List}s while one <em>built in code</em> keeps nested
	 * {@code JsonObject}s and {@code JsonArray}s. A Vert.x {@code JsonArray} is not a
	 * {@code java.util.List}, and {@code FilterPortResolver} - which lives in {@code node-model} and
	 * must not gain a Vert.x dependency - can only read the plain shape. Without this, a filter node
	 * whose buckets were built programmatically resolved <em>no bucket ports at all</em>, and an edge
	 * drawn to one failed validation at boot. Normalising once here is why the resolver, and every
	 * other {@code NodePortResolver}, only ever sees one shape.
	 * </p>
	 */
	private static JsonObject readOptions(JsonObject node) {
		JsonObject options = node.getJsonObject("options");
		if (options == null) {
			options = node.getJsonObject("config");
		}
		return options == null ? null : new JsonObject(options.encode());
	}

	private void applyEdges(String name, JsonArray edges, Set<String> nodeIds,
		Map<String, List<String>> dependencies, Map<String, Map<String, FilterBranch>> conditional,
		Map<String, List<InputBinding>> bindings) {

		Set<String> seenEdges = new LinkedHashSet<>();
		Set<String> seenDeps = new LinkedHashSet<>();
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

			String sourcePort = edge.getString("sourcePort");
			String targetPort = edge.getString("targetPort");
			if (sourcePort == null || sourcePort.isBlank() || targetPort == null || targetPort.isBlank()) {
				throw new GraphValidationException("Pipeline '" + name + "' edge " + from + "->" + to
					+ " at index " + i + " does not say which ports it connects."
					+ " Every edge must carry sourcePort and targetPort");
			}

			FilterBranch parsed = FilterBranch.ANY;
			String branch = edge.getString("branch");
			if (branch != null && !branch.isBlank()) {
				try {
					parsed = FilterBranch.valueOf(branch.toUpperCase());
				} catch (IllegalArgumentException e) {
					throw new GraphValidationException("Pipeline '" + name + "' edge " + from + "->" + to
						+ " has unknown branch '" + branch + "'; expected ANY, PASS or REJECT");
				}
			}

			// The dedupe key is the whole port tuple. Keying it on the node pair alone made two
			// edges between the same nodes on different ports indistinguishable, so one of them was
			// silently dropped.
			if (!seenEdges.add(from + "." + sourcePort + "->" + to + "." + targetPort)) {
				continue;
			}
			bindings.get(to).add(new InputBinding(targetPort, from, sourcePort, parsed));

			// Several ports of one node may be fed by the same upstream node; it is still a single
			// scheduling dependency.
			if (seenDeps.add(from + "->" + to)) {
				dependencies.get(to).add(from);
			}
			if (parsed != FilterBranch.ANY) {
				conditional.get(to).put(from, parsed);
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
