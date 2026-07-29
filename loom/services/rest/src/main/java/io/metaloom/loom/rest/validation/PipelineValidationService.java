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

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Service class for server-side pipeline validation.
 * 
 * <p>This service performs comprehensive validation of pipeline definitions,
 * including structural checks (node IDs, edges, cycles) and semantic checks
 * (node types against the descriptor registry). It mirrors the client-side
 * validation implemented in {@code PipelineEditor.tsx}.</p>
 * 
 * <p>Validation checks performed:</p>
 * <ul>
 *   <li>Node ID regex: {@code ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$}</li>
 *   <li>Unique node IDs</li>
 *   <li>Graph cycles (Kahn's algorithm)</li>
 *   <li>Unknown node types against the descriptor registry</li>
 *   <li>Edge references point to existing node IDs</li>
 *   <li>Every node is reachable from the pipeline source</li>
 *   <li>PASS/REJECT branch edges originate from a {@link NodeCategory#FILTER} node</li>
 * </ul>
 */
public class PipelineValidationService {

    private static final java.util.regex.Pattern NODE_ID_PATTERN = java.util.regex.Pattern.compile(
        "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    private final NodeDescriptorRegistry nodeDescriptorRegistry;

    public PipelineValidationService(NodeDescriptorRegistry nodeDescriptorRegistry) {
        this.nodeDescriptorRegistry = nodeDescriptorRegistry;
    }

    /**
     * Validates a pipeline definition JSON object.
     * 
     * @param definition the pipeline definition JSON
     * @throws ValidationException if any validation check fails
     */
    public void validateDefinition(JsonObject definition) {
        requireNonNull(definition, "A pipeline definition must be set");

        JsonArray nodes = definition.getJsonArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new ValidationException("Pipeline definition must contain at least one node");
        }

        Set<String> nodeIds = new HashSet<>();
        List<String> allNodeIds = new ArrayList<>();
        // Kept so the branch and reachability checks below can consult a node's kind and
        // whether it was explicitly marked as a source, without re-walking the array.
        Map<String, String> nodeTypes = new HashMap<>();
        Set<String> declaredSources = new LinkedHashSet<>();

        // Validate nodes
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject node = nodes.getJsonObject(i);
            if (node == null) {
                throw new ValidationException("Pipeline node at index " + i + " is null");
            }
            String id = node.getString("id");
            if (id == null || id.isBlank()) {
                throw new ValidationException("Pipeline node at index " + i + " is missing an id");
            }
            if (!NODE_ID_PATTERN.matcher(id).matches()) {
                throw new ValidationException("Invalid node ID: \"" + id
                    + "\" — IDs must match ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");
            }
            if (!nodeIds.add(id)) {
                throw new ValidationException("Duplicate node ID: \"" + id
                    + "\" — node IDs must be unique");
            }
            allNodeIds.add(id);

            String type = node.getString("type");
            if (type == null || type.isBlank()) {
                throw new ValidationException("Node \"" + id + "\" is missing a type");
            }

            // Validate node type against descriptor registry
            if (!nodeDescriptorRegistry.contains(type)) {
                throw new ValidationException(
                    "Unknown node type: \"" + type + "\" — not found in descriptor registry");
            }
            nodeTypes.put(id, type);
            if (node.getBoolean("source", false)) {
                declaredSources.add(id);
            }
        }

        // Validate edges
        JsonArray edges = definition.getJsonArray("edges");
        if (edges != null) {
            for (int i = 0; i < edges.size(); i++) {
                JsonObject edge = edges.getJsonObject(i);
                if (edge == null) {
                    throw new ValidationException("Pipeline edge at index " + i + " is null");
                }
                String source = edge.getString("source");
                String target = edge.getString("target");
                if (source == null || source.isBlank()) {
                    throw new ValidationException("Pipeline edge at index " + i + " is missing a source");
                }
                if (target == null || target.isBlank()) {
                    throw new ValidationException("Pipeline edge at index " + i + " is missing a target");
                }
                if (!nodeIds.contains(source)) {
                    throw new ValidationException("Pipeline edge source \"" + source
                        + "\" does not match any node ID");
                }
                if (!nodeIds.contains(target)) {
                    throw new ValidationException("Pipeline edge target \"" + target
                        + "\" does not match any node ID");
                }
            }

            // Cycle detection (Kahn's algorithm)
            if (hasCycle(allNodeIds, edges)) {
                throw new ValidationException(
                    "Cycle detected in pipeline graph — nodes form a circular dependency");
            }

            // A PASS/REJECT edge only makes sense downstream of a filter: those are the
            // only nodes that emit a filter_passed verdict for the branch to read.
            validateBranchesOriginateFromFilters(edges, nodeTypes);

            // A node the source cannot reach would never be dispatched. Silently ignoring
            // it is how a broken graph used to look like it ran green while doing nothing;
            // reject it instead.
            validateReachableFromSource(allNodeIds, edges, declaredSources);

            // Port wiring: the ports exist, their content types are compatible, required inputs
            // and XOR groups are satisfied, only sequence inputs take several edges, and the
            // fan-out shape is one the engine can execute.
            validatePorts(definition);
        }
    }

    /**
     * Check the port wiring by parsing the definition exactly as a run would.
     *
     * <p>
     * This deliberately <strong>delegates</strong> rather than reimplementing the rules.
     * Validation logic in this feature has historically existed in three independent copies that
     * drifted apart; the port rules live in {@code PortGraphAnalyzer} alone, and running the real
     * parser here means a definition that saves is a definition that starts. The translation to
     * {@link ValidationException} is all this method adds.
     * </p>
     */
    private void validatePorts(JsonObject definition) {
        try {
            new PipelineGraphParser(nodeDescriptorRegistry)
                .parse("definition", definition, true, false, 0);
        } catch (GraphValidationException e) {
            // The parser names the pipeline "definition" for want of anything better; strip that
            // so the message reads as advice about the graph the author is looking at.
            String message = e.getMessage();
            if (message != null) {
                message = message.replace("Pipeline 'definition' ", "");
            }
            throw new ValidationException(message == null ? "Invalid pipeline definition" : message);
        }
    }

    /**
     * Reject PASS/REJECT branch edges whose source node is not a filter.
     *
     * <p>Only a {@link NodeCategory#FILTER} node writes the {@code filter_passed}
     * verdict that a conditional edge routes on, so a branch declared off any other
     * kind can never be taken - it is a wiring mistake, not a valid pipeline.</p>
     */
    private void validateBranchesOriginateFromFilters(JsonArray edges, Map<String, String> nodeTypes) {
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
                throw new ValidationException("Edge \"" + source + "\" -> \"" + target
                    + "\" has unknown branch \"" + branch + "\" — expected ANY, PASS or REJECT");
            }
            String type = nodeTypes.get(source);
            NodeDescriptor descriptor = type == null ? null : nodeDescriptorRegistry.get(type);
            if (descriptor == null || descriptor.getCategory() != NodeCategory.FILTER) {
                throw new ValidationException("Edge \"" + source + "\" -> \"" + target + "\" declares branch "
                    + normalized + " but \"" + source + "\" is not a filter node — only filter nodes emit "
                    + "PASS/REJECT branches");
            }
        }
    }

    /**
     * Reject definitions with nodes that cannot be reached from the source.
     *
     * <p>The start set is the explicitly declared source(s) when present, otherwise the
     * dependency-free roots. Walking the directed graph from there, any node left
     * unvisited is an orphan: connected to nothing the source produces, so it can never
     * run. A common way to introduce one is to mark a source node explicitly and leave a
     * second, disconnected root behind.</p>
     */
    private void validateReachableFromSource(List<String> nodeIds, JsonArray edges, Set<String> declaredSources) {
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
            throw new ValidationException("Unreachable node(s): " + orphans
                + " — every node must be reachable from the pipeline source");
        }
    }

    /**
     * Detect whether the node/edge graph contains a cycle using Kahn's algorithm.
     *
     * @param nodeIds  list of all node IDs
     * @param edges    JSON array of edge objects with source/target
     * @return true if a cycle exists
     */
    private boolean hasCycle(List<String> nodeIds, JsonArray edges) {
        java.util.Map<String, List<String>> adj = new java.util.HashMap<>();
        java.util.Map<String, Integer> inDeg = new java.util.HashMap<>();
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
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
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
     * Require that the given object is not null.
     */
    private void requireNonNull(Object obj, String message) {
        if (obj == null) {
            throw new ValidationException(message);
        }
    }
}