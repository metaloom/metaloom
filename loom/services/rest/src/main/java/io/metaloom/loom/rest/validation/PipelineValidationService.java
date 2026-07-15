package io.metaloom.loom.rest.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
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