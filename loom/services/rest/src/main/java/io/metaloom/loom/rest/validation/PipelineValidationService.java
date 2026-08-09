package io.metaloom.loom.rest.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.rest.model.pipeline.PipelineValidationError;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Server-side pipeline validation, and the only authority on the rules.
 *
 * <p>
 * The structural checks (node id format, uniqueness, edge references, cycles, reachable-from-source,
 * branch-originates-from-filter) live here and nowhere else. Port rules are <strong>not</strong>
 * reimplemented — {@link #collectErrors(JsonObject)} runs the real {@link PipelineGraphParser}, so a
 * definition that validates is a definition that starts. Adding a second copy of either set is how
 * this feature previously ended up with three validators that disagreed.
 * </p>
 *
 * <p>
 * There are two ways in, and the difference is deliberate:
 * </p>
 * <ul>
 * <li>{@link #collectErrors(JsonObject)} reports <em>everything</em> wrong with the definition. It
 * backs {@code POST /api/v1/pipelines/validate}, where the caller is an author fixing a draft and
 * one problem per round trip is a bad way to spend their afternoon.</li>
 * <li>{@link #validateDefinition(JsonObject)} throws on the first error. It backs create and update,
 * where the definition is about to be stored and one reason not to store it is enough.</li>
 * </ul>
 *
 * <p>
 * Validation checks performed:
 * </p>
 * <ul>
 * <li>Node ID regex: {@code ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$}</li>
 * <li>Unique node IDs</li>
 * <li>Graph cycles (Kahn's algorithm)</li>
 * <li>Unknown node types against the descriptor registry</li>
 * <li>Edge references point to existing node IDs</li>
 * <li>Every node is reachable from the pipeline source</li>
 * <li>PASS/REJECT branch edges originate from a {@link NodeCategory#FILTER} node</li>
 * <li>Port wiring, delegated to {@link PipelineGraphParser}</li>
 * </ul>
 */
public class PipelineValidationService {

	private static final Pattern NODE_ID_PATTERN = Pattern.compile(
		"^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

	// ── Error codes ────────────────────────────────────────────────────────
	// Stable identifiers, and the only part of an error a client should branch on. The messages
	// beside them are for humans and are expected to be reworded.

	public static final String DEFINITION_MISSING = "DEFINITION_MISSING";
	public static final String NODES_NOT_ARRAY = "NODES_NOT_ARRAY";
	public static final String EDGES_NOT_ARRAY = "EDGES_NOT_ARRAY";
	public static final String EMPTY_GRAPH = "EMPTY_GRAPH";
	public static final String NODE_NULL = "NODE_NULL";
	public static final String NODE_ID_MISSING = "NODE_ID_MISSING";
	public static final String NODE_ID_INVALID = "NODE_ID_INVALID";
	public static final String NODE_ID_DUPLICATE = "NODE_ID_DUPLICATE";
	public static final String NODE_TYPE_MISSING = "NODE_TYPE_MISSING";
	public static final String NODE_TYPE_UNKNOWN = "NODE_TYPE_UNKNOWN";
	public static final String EDGE_NULL = "EDGE_NULL";
	public static final String EDGE_SOURCE_MISSING = "EDGE_SOURCE_MISSING";
	public static final String EDGE_TARGET_MISSING = "EDGE_TARGET_MISSING";
	public static final String EDGE_SOURCE_UNKNOWN = "EDGE_SOURCE_UNKNOWN";
	public static final String EDGE_TARGET_UNKNOWN = "EDGE_TARGET_UNKNOWN";
	public static final String BRANCH_UNKNOWN = "BRANCH_UNKNOWN";
	public static final String BRANCH_NOT_FILTER = "BRANCH_NOT_FILTER";
	public static final String CYCLE = "CYCLE";
	public static final String UNREACHABLE = "UNREACHABLE";
	public static final String PORTS = "PORTS";

	private final NodeDescriptorRegistry nodeDescriptorRegistry;

	public PipelineValidationService(NodeDescriptorRegistry nodeDescriptorRegistry) {
		this.nodeDescriptorRegistry = nodeDescriptorRegistry;
	}

	/**
	 * Validate a pipeline definition, throwing on the first problem.
	 *
	 * <p>
	 * A thin wrapper over {@link #collectErrors(JsonObject)}, kept because the create and update
	 * paths want exactly this: a definition is either stored or it is not, and the first reason is
	 * the one worth reporting in the 400.
	 * </p>
	 *
	 * @param definition
	 *            the pipeline definition JSON
	 * @throws ValidationException
	 *             if any validation check fails
	 */
	public void validateDefinition(JsonObject definition) {
		List<PipelineValidationError> errors = collectErrors(definition);
		if (!errors.isEmpty()) {
			throw new ValidationException(errors.get(0).getMessage());
		}
	}

	/**
	 * Validate a pipeline definition, reporting every problem found.
	 *
	 * <p>
	 * Checks run in dependency order and later phases are skipped when an earlier one has already
	 * made them meaningless: there is no point running cycle detection over edges whose endpoints do
	 * not name real nodes, and no point asking the parser about ports in a graph whose ids are
	 * duplicated. Within a phase every error is collected, which is what makes the common case — a
	 * handful of independent mistakes across a canvas — one round trip instead of five.
	 * </p>
	 *
	 * @param definition
	 *            the pipeline definition JSON; null is itself an error, not an exception
	 * @return the problems found, oldest phase first; empty when the definition would be accepted
	 */
	public List<PipelineValidationError> collectErrors(JsonObject definition) {
		List<PipelineValidationError> errors = new ArrayList<>();
		if (definition == null) {
			return List.of(error(DEFINITION_MISSING, "A pipeline definition must be set"));
		}

		// A client that sends the wrong shape must get an answer, not an internal error: reading it
		// straight through getJsonArray throws ClassCastException when "nodes" is present but is,
		// say, an object.
		Object rawNodes = definition.getValue("nodes");
		if (rawNodes != null && !(rawNodes instanceof JsonArray)) {
			return List.of(error(NODES_NOT_ARRAY, "Pipeline definition field \"nodes\" must be an array of nodes"));
		}
		JsonArray nodes = (JsonArray) rawNodes;
		if (nodes == null || nodes.isEmpty()) {
			return List.of(error(EMPTY_GRAPH, "Pipeline definition must contain at least one node"));
		}
		Object rawEdges = definition.getValue("edges");
		if (rawEdges != null && !(rawEdges instanceof JsonArray)) {
			return List.of(error(EDGES_NOT_ARRAY, "Pipeline definition field \"edges\" must be an array of edges"));
		}
		JsonArray edges = (JsonArray) rawEdges;

		Set<String> nodeIds = new LinkedHashSet<>();
		List<String> allNodeIds = new ArrayList<>();
		// Kept so the branch and reachability checks below can consult a node's kind and
		// whether it was explicitly marked as a source, without re-walking the array.
		Map<String, String> nodeTypes = new HashMap<>();
		Set<String> declaredSources = new LinkedHashSet<>();

		collectNodeErrors(nodes, errors, nodeIds, allNodeIds, nodeTypes, declaredSources);

		// Every remaining check reads the graph through its node ids. When one of them is missing,
		// malformed or duplicated the graph is not yet a graph, and running the edge and port checks
		// over it would bury the errors the author has to fix first under derived noise.
		if (!errors.isEmpty()) {
			return errors;
		}
		if (edges == null) {
			return errors;
		}

		collectEdgeErrors(edges, nodeIds, errors);
		if (!errors.isEmpty()) {
			return errors;
		}

		if (hasCycle(allNodeIds, edges)) {
			errors.add(error(CYCLE, "Cycle detected in pipeline graph — nodes form a circular dependency"));
		}

		// A PASS/REJECT edge only makes sense downstream of a filter: those are the
		// only nodes that emit a filter_passed verdict for the branch to read.
		collectBranchErrors(edges, nodeTypes, errors);

		// A node the source cannot reach would never be dispatched. Silently ignoring
		// it is how a broken graph used to look like it ran green while doing nothing;
		// reject it instead. Meaningless on a cyclic graph, where every node on the cycle
		// is unreachable as a consequence of the cycle already reported.
		if (errors.stream().noneMatch(e -> CYCLE.equals(e.getCode()))) {
			collectReachabilityErrors(allNodeIds, edges, declaredSources, errors);
		}

		// Port wiring: the ports exist, their content types are compatible, required inputs
		// and XOR groups are satisfied, only sequence inputs take several edges, and the
		// fan-out shape is one the engine can execute. The parser stops at the first problem,
		// so this contributes at most one error — running it over a graph that is already
		// structurally broken would only restate what is above.
		if (errors.isEmpty()) {
			collectPortErrors(definition, errors);
		}

		return errors;
	}

	/**
	 * Per-node checks. Fills {@code nodeIds}, {@code allNodeIds}, {@code nodeTypes} and
	 * {@code declaredSources} as a side effect so the graph-level checks below do not re-walk the
	 * array.
	 *
	 * <p>
	 * A node that fails a check still contributes its id to the set where it has one: an author with
	 * a mistyped node kind should not also be told that every edge touching that node dangles.
	 * </p>
	 */
	private void collectNodeErrors(JsonArray nodes, List<PipelineValidationError> errors, Set<String> nodeIds,
		List<String> allNodeIds, Map<String, String> nodeTypes, Set<String> declaredSources) {
		for (int i = 0; i < nodes.size(); i++) {
			JsonObject node = nodes.getJsonObject(i);
			if (node == null) {
				errors.add(error(NODE_NULL, "Pipeline node at index " + i + " is null"));
				continue;
			}
			String id = node.getString("id");
			if (id == null || id.isBlank()) {
				errors.add(error(NODE_ID_MISSING, "Pipeline node at index " + i + " is missing an id"));
				continue;
			}
			if (!NODE_ID_PATTERN.matcher(id).matches()) {
				errors.add(nodeError(NODE_ID_INVALID, "Invalid node ID: \"" + id
					+ "\" — IDs must match ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$", id));
			} else if (!nodeIds.add(id)) {
				errors.add(nodeError(NODE_ID_DUPLICATE, "Duplicate node ID: \"" + id
					+ "\" — node IDs must be unique", id));
			} else {
				allNodeIds.add(id);
			}

			String type = node.getString("type");
			if (type == null || type.isBlank()) {
				errors.add(nodeError(NODE_TYPE_MISSING, "Node \"" + id + "\" is missing a type", id));
				continue;
			}
			if (!nodeDescriptorRegistry.contains(type)) {
				errors.add(nodeError(NODE_TYPE_UNKNOWN,
					"Unknown node type: \"" + type + "\" — not found in descriptor registry", id));
				continue;
			}
			nodeTypes.put(id, type);
			if (node.getBoolean("source", false)) {
				declaredSources.add(id);
			}
		}
	}

	/** Per-edge shape and reference checks. */
	private void collectEdgeErrors(JsonArray edges, Set<String> nodeIds, List<PipelineValidationError> errors) {
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			if (edge == null) {
				errors.add(error(EDGE_NULL, "Pipeline edge at index " + i + " is null"));
				continue;
			}
			String source = edge.getString("source");
			String target = edge.getString("target");
			if (source == null || source.isBlank()) {
				errors.add(edgeError(EDGE_SOURCE_MISSING, "Pipeline edge at index " + i + " is missing a source",
					source, target));
				continue;
			}
			if (target == null || target.isBlank()) {
				errors.add(edgeError(EDGE_TARGET_MISSING, "Pipeline edge at index " + i + " is missing a target",
					source, target));
				continue;
			}
			if (!nodeIds.contains(source)) {
				errors.add(edgeError(EDGE_SOURCE_UNKNOWN, "Pipeline edge source \"" + source
					+ "\" does not match any node ID", source, target));
			}
			if (!nodeIds.contains(target)) {
				errors.add(edgeError(EDGE_TARGET_UNKNOWN, "Pipeline edge target \"" + target
					+ "\" does not match any node ID", source, target));
			}
		}
	}

	/**
	 * Check the port wiring by parsing the definition exactly as a run would.
	 *
	 * <p>
	 * This deliberately <strong>delegates</strong> rather than reimplementing the rules. Validation
	 * logic in this feature has historically existed in three independent copies that drifted apart;
	 * the port rules live in {@code PortGraphAnalyzer} alone, and running the real parser here means
	 * a definition that saves is a definition that starts. The translation to an error entry is all
	 * this method adds.
	 * </p>
	 */
	private void collectPortErrors(JsonObject definition, List<PipelineValidationError> errors) {
		try {
			new PipelineGraphParser(nodeDescriptorRegistry)
				.parse("definition", definition, true, false, 0);
		} catch (GraphValidationException e) {
			errors.add(error(PORTS, stripGraphName(e.getMessage())));
		}
	}

	/**
	 * The parser names the pipeline {@code "definition"} for want of anything better; strip that so
	 * the message reads as advice about the graph the author is looking at.
	 */
	public static String stripGraphName(String message) {
		if (message == null) {
			return "Invalid pipeline definition";
		}
		return message.replace("Pipeline 'definition' ", "");
	}

	/**
	 * Reject PASS/REJECT branch edges whose source node is not a filter.
	 *
	 * <p>
	 * Only a {@link NodeCategory#FILTER} node writes the {@code filter_passed} verdict that a
	 * conditional edge routes on, so a branch declared off any other kind can never be taken - it is
	 * a wiring mistake, not a valid pipeline.
	 * </p>
	 */
	private void collectBranchErrors(JsonArray edges, Map<String, String> nodeTypes, List<PipelineValidationError> errors) {
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			String branch = edge.getString("branch");
			if (branch == null || branch.isBlank()) {
				continue;
			}
			String normalized = branch.trim().toUpperCase();
			if (normalized.equals("ANY")) {
				continue;
			}
			String source = edge.getString("source");
			String target = edge.getString("target");
			if (!normalized.equals("PASS") && !normalized.equals("REJECT")) {
				errors.add(edgeError(BRANCH_UNKNOWN, "Edge \"" + source + "\" -> \"" + target
					+ "\" has unknown branch \"" + branch + "\" — expected ANY, PASS or REJECT", source, target));
				continue;
			}
			String type = nodeTypes.get(source);
			NodeDescriptor descriptor = type == null ? null : nodeDescriptorRegistry.get(type);
			if (descriptor == null || descriptor.getCategory() != NodeCategory.FILTER) {
				errors.add(edgeError(BRANCH_NOT_FILTER, "Edge \"" + source + "\" -> \"" + target + "\" declares branch "
					+ normalized + " but \"" + source + "\" is not a filter node — only filter nodes emit "
					+ "PASS/REJECT branches", source, target));
			}
		}
	}

	/**
	 * Reject definitions with nodes that cannot be reached from the source.
	 *
	 * <p>
	 * The start set is the explicitly declared source(s) when present, otherwise the dependency-free
	 * roots. Walking the directed graph from there, any node left unvisited is an orphan: connected
	 * to nothing the source produces, so it can never run. A common way to introduce one is to mark
	 * a source node explicitly and leave a second, disconnected root behind.
	 * </p>
	 */
	private void collectReachabilityErrors(List<String> nodeIds, JsonArray edges, Set<String> declaredSources,
		List<PipelineValidationError> errors) {
		Map<String, List<String>> adjacency = new HashMap<>();
		Map<String, Integer> inDegree = new HashMap<>();
		for (String id : nodeIds) {
			adjacency.put(id, new ArrayList<>());
			inDegree.put(id, 0);
		}
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			String source = edge.getString("source");
			String target = edge.getString("target");
			if (source != null && target != null && adjacency.containsKey(source) && inDegree.containsKey(target)) {
				adjacency.get(source).add(target);
				inDegree.put(target, inDegree.get(target) + 1);
			}
		}

		Set<String> start = new LinkedHashSet<>();
		if (!declaredSources.isEmpty()) {
			start.addAll(declaredSources);
		} else {
			for (String id : nodeIds) {
				if (inDegree.get(id) == 0) {
					start.add(id);
				}
			}
		}
		if (start.isEmpty()) {
			// No root at all means every node is on a cycle, which cycle detection has
			// already rejected. Nothing left to say here.
			return;
		}

		Set<String> reachable = new HashSet<>(start);
		Deque<String> queue = new ArrayDeque<>(start);
		while (!queue.isEmpty()) {
			String id = queue.poll();
			for (String target : adjacency.get(id)) {
				if (reachable.add(target)) {
					queue.add(target);
				}
			}
		}

		if (reachable.size() != nodeIds.size()) {
			List<String> orphans = new ArrayList<>(nodeIds);
			orphans.removeAll(reachable);
			// One error per orphan, each pinned to its node, so the editor can mark them on the
			// canvas — but the message keeps naming the whole set, because that is what makes an
			// orphan understandable: the author is looking at a second, disconnected root.
			String message = "Unreachable node(s): " + orphans
				+ " — every node must be reachable from the pipeline source";
			for (String orphan : orphans) {
				errors.add(nodeError(UNREACHABLE, message, orphan));
			}
		}
	}

	/**
	 * Detect whether the node/edge graph contains a cycle using Kahn's algorithm.
	 *
	 * @param nodeIds
	 *            list of all node IDs
	 * @param edges
	 *            JSON array of edge objects with source/target
	 * @return true if a cycle exists
	 */
	private boolean hasCycle(List<String> nodeIds, JsonArray edges) {
		Map<String, List<String>> adj = new HashMap<>();
		Map<String, Integer> inDeg = new HashMap<>();
		for (String id : nodeIds) {
			adj.put(id, new ArrayList<>());
			inDeg.put(id, 0);
		}
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			String source = edge.getString("source");
			String target = edge.getString("target");
			if (source != null && target != null && adj.containsKey(source) && inDeg.containsKey(target)) {
				adj.get(source).add(target);
				inDeg.put(target, inDeg.get(target) + 1);
			}
		}
		Deque<String> queue = new ArrayDeque<>();
		for (var e : inDeg.entrySet()) {
			if (e.getValue() == 0) {
				queue.add(e.getKey());
			}
		}
		int visited = 0;
		while (!queue.isEmpty()) {
			String id = queue.poll();
			visited++;
			for (String target : adj.get(id)) {
				int d = inDeg.get(target) - 1;
				inDeg.put(target, d);
				if (d == 0) {
					queue.add(target);
				}
			}
		}
		return visited < nodeIds.size();
	}

	/**
	 * Check a set of node options against the parameters that node kind declares.
	 *
	 * <p>
	 * Lives here rather than at the call site because this class already owns the descriptor
	 * registry — and because the options in a pipeline definition and the options in a re-execution
	 * request must be judged by the same rules, whichever door they came in through.
	 * </p>
	 *
	 * @param nodeType
	 *            the node kind, e.g. {@code facedetect}
	 * @param options
	 *            the options to check; null or empty always passes
	 * @throws ValidationException
	 *             naming the offending key and why it was rejected
	 */
	public void validateNodeOptions(String nodeType, Map<String, Object> options) {
		NodeOptionValidator.validate(nodeDescriptorRegistry.get(nodeType), options);
	}

	private static PipelineValidationError error(String code, String message) {
		return new PipelineValidationError(code, message, null, null);
	}

	private static PipelineValidationError nodeError(String code, String message, String nodeId) {
		return new PipelineValidationError(code, message, nodeId, null);
	}

	/** Definitions carry no edge ids, so an edge is named by the pair it connects. */
	private static PipelineValidationError edgeError(String code, String message, String source, String target) {
		return new PipelineValidationError(code, message, null, source + "->" + target);
	}
}
