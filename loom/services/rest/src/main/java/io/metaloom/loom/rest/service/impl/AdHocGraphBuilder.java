package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Turns an ad-hoc execution request into a definition the ordinary pipeline machinery accepts.
 *
 * <h2>Why there is a source node at all</h2>
 *
 * <p>
 * A {@code PipelineGraph} has exactly one source; the parser refuses a graph without one. An ad-hoc
 * run has no filesystem to walk, though - the caller already named the assets and Loom already knows
 * their paths - so the source is {@code loom-fetch}, the one node kind Loom executes itself.
 * {@code PipelineRunEngine.onItemDiscovered} synthesises its {@code media} output locally and no
 * {@code SOURCE_TASK} is ever sent. That is what makes an ad-hoc run possible without a worker that
 * advertises a source kind.
 * </p>
 *
 * <p>
 * Everything produced here is the same definition format {@code validate_pipeline} and the pipeline
 * editor use, so a caller can validate an ad-hoc graph with the existing tool and there is no second
 * schema to keep in step.
 * </p>
 */
public final class AdHocGraphBuilder {

	/** The Loom-executed source kind; see {@code OrphanNodeDescriptorProvider}. */
	public static final String SOURCE_KIND = "loom-fetch";

	/** Node id of the injected source. Short and fixed, so an error message can name it. */
	public static final String SOURCE_NODE_ID = "src";

	/** The port {@code loom-fetch} hands the media out on, and the port a probe binds to. */
	public static final String MEDIA_PORT = "media";

	/** Node ids are validated against this by {@code PipelineValidationService}. */
	private static final String NODE_ID_PATTERN = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$";

	private AdHocGraphBuilder() {
	}

	/**
	 * The one-node definition a probe runs: {@code loom-fetch} feeding the requested kind.
	 *
	 * <p>
	 * The media edge binds to the receiving node's {@code media} input port, which every media-consuming
	 * node declares. A kind that declares no such port is rejected by the parser with a message naming
	 * the port, which is the honest answer - it is not a node you can point at an asset.
	 * </p>
	 *
	 * @param kind    the node kind to run
	 * @param options the node options, already validated against the descriptor
	 */
	public static JsonObject singleNodeDefinition(String kind, Map<String, Object> options) {
		String nodeId = nodeIdFor(kind);
		JsonObject node = new JsonObject()
			.put("id", nodeId)
			.put("type", kind);
		if (options != null && !options.isEmpty()) {
			node.put("options", new JsonObject(options));
		}

		return new JsonObject()
			.put("version", 1)
			.put("name", "probe " + kind)
			.put("nodes", new JsonArray()
				.add(sourceNode())
				.add(node))
			.put("edges", new JsonArray()
				.add(edge("e-src-" + nodeId, SOURCE_NODE_ID, MEDIA_PORT, nodeId, MEDIA_PORT)));
	}

	/**
	 * Ensure a submitted definition is rooted in {@code loom-fetch}.
	 *
	 * <p>
	 * A definition that already declares the {@code loom-fetch} source is returned untouched. One that
	 * declares a different source is <b>rejected</b> rather than rewritten: a {@code filesystem-source}
	 * or {@code s3-source} would enumerate a second, unrelated set of media on a worker, so the run
	 * would silently process something other than the assets the caller named.
	 * </p>
	 *
	 * <p>
	 * Otherwise a source is prepended and wired to every node that has no inbound edge, which is what
	 * lets a caller submit just the interesting nodes.
	 * </p>
	 *
	 * @throws ValidationException when the definition is unusable as an ad-hoc graph
	 */
	public static JsonObject withLoomFetchSource(JsonObject definition) {
		if (definition == null) {
			throw new ValidationException("A definition is required.");
		}
		JsonArray nodes = definition.getJsonArray("nodes");
		if (nodes == null || nodes.isEmpty()) {
			throw new ValidationException("The definition declares no nodes.");
		}

		Set<String> nodeIds = new LinkedHashSet<>();
		String existingSourceId = null;
		for (int i = 0; i < nodes.size(); i++) {
			JsonObject node = nodes.getJsonObject(i);
			if (node == null) {
				throw new ValidationException("The definition contains an empty node entry.");
			}
			String id = node.getString("id");
			if (id == null || id.isBlank()) {
				throw new ValidationException("Every node needs an id.");
			}
			nodeIds.add(id);
			String kind = node.getString("type", node.getString("kind"));
			boolean declaredSource = node.getBoolean("source", false) || SOURCE_KIND.equals(kind);
			if (declaredSource) {
				if (!SOURCE_KIND.equals(kind)) {
					throw new ValidationException("An ad-hoc run takes its media from Loom, so its source must be '"
						+ SOURCE_KIND + "'; node '" + id + "' declares '" + kind + "'. Remove the source node and Loom will add one.");
				}
				existingSourceId = id;
			}
		}

		if (existingSourceId != null) {
			return definition;
		}

		JsonArray edges = definition.getJsonArray("edges", new JsonArray());
		Set<String> hasInbound = new LinkedHashSet<>();
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			if (edge != null && edge.getString("target") != null) {
				hasInbound.add(edge.getString("target"));
			}
		}

		List<String> roots = new ArrayList<>(nodeIds);
		roots.removeAll(hasInbound);
		if (roots.isEmpty()) {
			// Every node is fed by another one, so there is nowhere to attach the media. That is a
			// cycle or a dangling edge; the parser will say which, but it must not be handed a graph
			// with a source wired to nothing.
			throw new ValidationException("The definition has no node without an inbound edge to attach the media source to.");
		}
		if (nodeIds.contains(SOURCE_NODE_ID)) {
			throw new ValidationException("Node id '" + SOURCE_NODE_ID + "' is reserved for the media source.");
		}

		JsonArray newNodes = new JsonArray();
		newNodes.add(sourceNode());
		nodes.forEach(newNodes::add);

		JsonArray newEdges = new JsonArray();
		edges.forEach(newEdges::add);
		for (String root : roots) {
			newEdges.add(edge("e-" + SOURCE_NODE_ID + "-" + root, SOURCE_NODE_ID, MEDIA_PORT, root, MEDIA_PORT));
		}

		return definition.copy()
			.put("nodes", newNodes)
			.put("edges", newEdges);
	}

	/**
	 * A node id derived from a kind, conforming to the id pattern the validator enforces.
	 *
	 * <p>
	 * Kinds are already lowercase-with-hyphens in practice, but the id is what ends up in the run
	 * record and in error messages, so it is sanitised rather than trusted.
	 * </p>
	 */
	public static String nodeIdFor(String kind) {
		String candidate = kind == null ? "" : kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
		candidate = candidate.replaceAll("^-+", "").replaceAll("-+$", "");
		if (candidate.length() > 64) {
			candidate = candidate.substring(0, 64).replaceAll("-+$", "");
		}
		if (!candidate.matches(NODE_ID_PATTERN)) {
			// Nothing usable survived. "node" is always valid and the kind is still carried by the
			// node's type, so the run remains readable.
			return "node";
		}
		return candidate;
	}

	private static JsonObject sourceNode() {
		return new JsonObject()
			.put("id", SOURCE_NODE_ID)
			.put("type", SOURCE_KIND)
			.put("source", true);
	}

	private static JsonObject edge(String id, String source, String sourcePort, String target, String targetPort) {
		return new JsonObject()
			.put("id", id)
			.put("source", source)
			.put("sourcePort", sourcePort)
			.put("target", target)
			.put("targetPort", targetPort);
	}

}
