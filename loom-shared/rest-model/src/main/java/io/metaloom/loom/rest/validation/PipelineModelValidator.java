package io.metaloom.loom.rest.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface PipelineModelValidator extends ModelValidator {

	default void validate(PipelineUpdateRequest request) {
		if (request != null && request.getDefinition() != null) {
			validateDefinition(request.getDefinition());
		}
	}

	default void validate(PipelineResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNull(response.getVersionUuid(), "A pipeline version UUID must be set");
		requireNonNull(response.getVersionNumber(), "A pipeline version number must be set");
		requireNonNullOrEmpty(response.getName(), "A pipeline name must be set");
		requireNonNull(response.getDefinition(), "A pipeline definition must be set");
	}

	default void validate(PipelineCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A pipeline name must be set");
		requireNonNull(request.getDefinition(), "A pipeline definition must be set");
		validateDefinition(request.getDefinition());
	}

	/**
	 * Validates the structural integrity of a pipeline definition JSON object.
	 *
	 * <p>Checks performed:</p>
	 * <ul>
	 *   <li>The definition contains a non-null {@code nodes} array.</li>
	 *   <li>Each node has a non-empty {@code id} matching
	 *       {@code ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$}.</li>
	 *   <li>Node IDs are unique within the definition.</li>
	 *   <li>Each node has a non-empty {@code type} (node kind).</li>
	 *   <li>Edge references (source/target) point to existing node IDs.</li>
	 *   <li>The graph defined by nodes + edges has no cycles (Kahn's algorithm).</li>
	 * </ul>
	 *
	 * <p>Node-type validation against the descriptor registry is performed
	 * server-side in the endpoint service, not here, because the validator
	 * interface does not have access to the registry.</p>
	 *
	 * @param definition the pipeline definition JSON
	 * @throws ValidationException if any structural check fails
	 */
	default void validateDefinition(JsonObject definition) {
		requireNonNull(definition, "A pipeline definition must be set");

		// A client that sends the wrong shape must get a 400, not an internal error: reading
		// it straight through getJsonArray throws ClassCastException when "nodes" is present
		// but is, say, an object.
		Object rawNodes = definition.getValue("nodes");
		if (rawNodes != null && !(rawNodes instanceof JsonArray)) {
			throw new ValidationException("Pipeline definition field \"nodes\" must be an array of nodes");
		}
		JsonArray nodes = (JsonArray) rawNodes;
		if (nodes == null || nodes.isEmpty()) {
			throw new ValidationException("Pipeline definition must contain at least one node");
		}

		Set<String> nodeIds = new HashSet<>();
		List<String> allNodeIds = new ArrayList<>();
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
		}

		Object rawEdges = definition.getValue("edges");
		if (rawEdges != null && !(rawEdges instanceof JsonArray)) {
			throw new ValidationException("Pipeline definition field \"edges\" must be an array of edges");
		}
		JsonArray edges = (JsonArray) rawEdges;
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

	/** Regex pattern for valid pipeline node IDs. */
	java.util.regex.Pattern NODE_ID_PATTERN = java.util.regex.Pattern.compile(
		"^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

	/**
	 * Detect whether the node/edge graph contains a cycle using Kahn's algorithm.
	 *
	 * @param nodeIds  list of all node IDs
	 * @param edges    JSON array of edge objects with source/target
	 * @return true if a cycle exists
	 */
	default boolean hasCycle(List<String> nodeIds, JsonArray edges) {
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
}
