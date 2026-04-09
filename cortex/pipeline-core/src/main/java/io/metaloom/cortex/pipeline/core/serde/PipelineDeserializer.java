package io.metaloom.cortex.pipeline.core.serde;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;

/**
 * Deserializer that parses a JSON document into a {@link Pipeline} that can be used for processing.
 * The JSON must define exactly one source node (identified by {@code "type": "source"} or
 * via the top-level {@code "sourceNode"} field).
 *
 * <p>By default, parsed nodes are structural stubs that return success immediately.
 * Use {@link #setNodeResolver(NodeResolver)} to provide a factory that maps node definitions
 * to concrete processing implementations.</p>
 *
 * <p>Expected JSON structure:</p>
 * <pre>
 * {
 *   "name": "video-analysis",
 *   "description": "Full processing for video",
 *   "priority": 100,
 *   "enabled": true,
 *   "dryRun": false,
 *   "sourceNode": "filesystem",
 *   "nodes": [
 *     {
 *       "id": "filesystem",
 *       "name": "Filesystem Source",
 *       "type": "source",
 *       "mode": "PARALLEL",
 *       "blocking": true,
 *       "concurrency": 4,
 *       "syncToLoom": false,
 *       "dependencies": [],
 *       "conditionalDependencies": {},
 *       "options": {}
 *     }
 *   ]
 * }
 * </pre>
 */
@Singleton
public class PipelineDeserializer {

	private final ObjectMapper mapper;
	private NodeResolver nodeResolver;

	@Inject
	public PipelineDeserializer(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Set a resolver that can map a deserialized node definition to a concrete {@link PipelineNode}.
	 * If the resolver returns {@code null} for a node, a stub node is used.
	 *
	 * @param nodeResolver the resolver
	 */
	public void setNodeResolver(NodeResolver nodeResolver) {
		this.nodeResolver = nodeResolver;
	}

	/**
	 * Deserialize a JSON string into a {@link Pipeline}.
	 *
	 * @param json the JSON string
	 * @return the deserialized pipeline
	 * @throws JsonProcessingException if parsing fails
	 */
	public Pipeline deserialize(String json) throws JsonProcessingException {
		JsonNode root = mapper.readTree(json);
		return fromJsonNode(root);
	}

	/**
	 * Deserialize a Jackson JsonNode into a {@link Pipeline}.
	 *
	 * @param root the JSON tree
	 * @return the deserialized pipeline
	 */
	public Pipeline fromJsonNode(JsonNode root) {
		String name = root.path("name").asText("unnamed");
		String description = root.path("description").asText("");
		int priority = root.path("priority").asInt(0);
		boolean enabled = root.path("enabled").asBoolean(true);
		boolean dryRun = root.path("dryRun").asBoolean(false);
		String sourceNodeId = root.path("sourceNode").asText(null);

		DefaultPipeline.Builder builder = DefaultPipeline.builder(name)
				.description(description)
				.priority(priority)
				.enabled(enabled)
				.dryRun(dryRun);

		JsonNode nodesArray = root.path("nodes");
		if (nodesArray.isArray()) {
			for (JsonNode nodeDef : nodesArray) {
				PipelineNode node = parseNode(nodeDef, sourceNodeId);
				if (node != null) {
					builder.addNode(node);
				}
			}
		}

		return builder.build();
	}

	private PipelineNode parseNode(JsonNode nodeDef, String sourceNodeId) {
		String id = nodeDef.path("id").asText(null);
		if (id == null) {
			return null;
		}

		// Try the custom resolver first
		if (nodeResolver != null) {
			PipelineNode resolved = nodeResolver.resolve(id, nodeDef);
			if (resolved != null) {
				return resolved;
			}
		}

		String nodeName = nodeDef.path("name").asText(id);
		String type = nodeDef.path("type").asText("processor");
		NodeMode mode = parseEnum(nodeDef.path("mode").asText("PARALLEL"), NodeMode.class, NodeMode.PARALLEL);
		boolean blocking = nodeDef.path("blocking").asBoolean(true);
		int concurrency = nodeDef.path("concurrency").asInt(1);
		boolean syncToLoom = nodeDef.path("syncToLoom").asBoolean(false);
		boolean source = "source".equals(type) || id.equals(sourceNodeId);

		// Parse dependencies
		Set<String> dependencies = new HashSet<>();
		JsonNode depsNode = nodeDef.path("dependencies");
		if (depsNode.isArray()) {
			for (JsonNode dep : depsNode) {
				dependencies.add(dep.asText());
			}
		}

		// Parse conditional dependencies
		Map<String, FilterBranch> conditionalDeps = new HashMap<>();
		JsonNode condNode = nodeDef.path("conditionalDependencies");
		if (condNode.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = condNode.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				FilterBranch branch = parseEnum(entry.getValue().asText("ANY"), FilterBranch.class, FilterBranch.ANY);
				conditionalDeps.put(entry.getKey(), branch);
			}
		}

		// Parse options
		Map<String, Object> options = new HashMap<>();
		JsonNode optionsNode = nodeDef.path("options");
		if (optionsNode.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = optionsNode.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				options.put(entry.getKey(), toJavaValue(entry.getValue()));
			}
		}

		return new DeserializedNode(id, nodeName, mode, blocking, dependencies, concurrency,
				syncToLoom, conditionalDeps, options, source);
	}

	private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, E defaultValue) {
		try {
			return Enum.valueOf(enumType, value);
		} catch (IllegalArgumentException e) {
			return defaultValue;
		}
	}

	private static Object toJavaValue(JsonNode node) {
		if (node.isTextual()) {
			return node.asText();
		} else if (node.isBoolean()) {
			return node.asBoolean();
		} else if (node.isIntegralNumber()) {
			return node.asLong();
		} else if (node.isFloatingPointNumber()) {
			return node.asDouble();
		} else if (node.isArray()) {
			var list = new java.util.ArrayList<>();
			for (JsonNode element : node) {
				list.add(toJavaValue(element));
			}
			return list;
		} else if (node.isObject()) {
			Map<String, Object> map = new HashMap<>();
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				map.put(entry.getKey(), toJavaValue(entry.getValue()));
			}
			return map;
		}
		return null;
	}

	/**
	 * A deserialized pipeline node that carries configuration from JSON.
	 * By default it returns success immediately. Use {@link NodeResolver} to replace
	 * with concrete implementations.
	 */
	private static class DeserializedNode extends AbstractPipelineNode {

		private final Map<String, Object> options;
		private final boolean source;

		DeserializedNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, boolean syncToLoom,
				Map<String, FilterBranch> conditionalDeps, Map<String, Object> options,
				boolean source) {
			super(id, name, mode, blocking, dependencies, concurrency, syncToLoom, conditionalDeps);
			this.options = options != null ? Collections.unmodifiableMap(options) : Collections.emptyMap();
			this.source = source;
		}

		@Override
		public boolean isSource() {
			return source;
		}

		@Override
		public Map<String, Object> options() {
			return options;
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			return NodeResult.success(id(), 0);
		}
	}

	/**
	 * Strategy interface for resolving deserialized node definitions into concrete
	 * {@link PipelineNode} implementations.
	 */
	@FunctionalInterface
	public interface NodeResolver {

		/**
		 * Resolve a node definition to a concrete PipelineNode.
		 *
		 * @param id   the node identifier
		 * @param def  the full JSON node definition
		 * @return a concrete PipelineNode, or {@code null} to use the default stub
		 */
		PipelineNode resolve(String id, JsonNode def);
	}
}
