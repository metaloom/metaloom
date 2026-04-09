package io.metaloom.cortex.pipeline.core.serde;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * Serializer that converts a {@link Pipeline} into a JSON representation.
 * The JSON encodes the full pipeline graph as a tree structure with branches,
 * filter nodes, source nodes, and processing nodes.
 *
 * <p>Example output:</p>
 * <pre>
 * {
 *   "name": "video-analysis",
 *   "description": "Full processing for video",
 *   "priority": 100,
 *   "enabled": true,
 *   "dryRun": false,
 *   "nodes": [
 *     {
 *       "id": "sha512",
 *       "name": "SHA-512 Hash",
 *       "type": "source",
 *       "mode": "PARALLEL",
 *       "blocking": true,
 *       "concurrency": 4,
 *       "syncToLoom": true,
 *       "dependencies": [],
 *       "conditionalDependencies": {},
 *       "options": {},
 *       "children": ["tika"]
 *     }
 *   ],
 *   "tree": {
 *     "roots": ["sha512"],
 *     "branches": {
 *       "sha512": ["tika"],
 *       "tika": ["llm"]
 *     }
 *   }
 * }
 * </pre>
 */
public class PipelineSerializer {

	private final ObjectMapper mapper;

	public PipelineSerializer() {
		this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	}

	public PipelineSerializer(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Serialize a pipeline to a JSON string.
	 *
	 * @param pipeline the pipeline to serialize
	 * @return JSON string representation
	 * @throws JsonProcessingException if serialization fails
	 */
	public String serialize(Pipeline pipeline) throws JsonProcessingException {
		ObjectNode root = toObjectNode(pipeline);
		return mapper.writeValueAsString(root);
	}

	/**
	 * Serialize a pipeline to a Jackson ObjectNode.
	 *
	 * @param pipeline the pipeline to serialize
	 * @return ObjectNode representation
	 */
	public ObjectNode toObjectNode(Pipeline pipeline) {
		ObjectNode root = mapper.createObjectNode();
		root.put("name", pipeline.name());
		root.put("description", pipeline.description());
		root.put("priority", pipeline.priority());
		root.put("enabled", pipeline.isEnabled());
		root.put("dryRun", pipeline.isDryRun());

		// Serialize nodes
		List<PipelineNode> nodes = pipeline.nodes();
		ArrayNode nodesArray = mapper.createArrayNode();

		// Build reverse dependency map: parent → children
		Map<String, List<String>> childrenMap = buildChildrenMap(nodes);

		for (PipelineNode node : nodes) {
			ObjectNode nodeObj = serializeNode(node, childrenMap);
			nodesArray.add(nodeObj);
		}
		root.set("nodes", nodesArray);

		// Serialize tree structure
		ObjectNode tree = buildTreeStructure(nodes, childrenMap);
		root.set("tree", tree);

		return root;
	}

	private ObjectNode serializeNode(PipelineNode node, Map<String, List<String>> childrenMap) {
		ObjectNode obj = mapper.createObjectNode();
		obj.put("id", node.id());
		obj.put("name", node.name());
		obj.put("type", resolveNodeType(node));
		obj.put("mode", node.mode().name());
		obj.put("blocking", node.isBlocking());
		obj.put("concurrency", node.concurrency());
		obj.put("syncToLoom", node.syncToLoom());

		// Dependencies
		ArrayNode deps = mapper.createArrayNode();
		for (String dep : node.dependencies()) {
			deps.add(dep);
		}
		obj.set("dependencies", deps);

		// Conditional dependencies
		ObjectNode condDeps = mapper.createObjectNode();
		for (Map.Entry<String, FilterBranch> entry : node.conditionalDependencies().entrySet()) {
			condDeps.put(entry.getKey(), entry.getValue().name());
		}
		obj.set("conditionalDependencies", condDeps);

		// Options
		ObjectNode options = mapper.valueToTree(node.options());
		obj.set("options", options);

		// Children (downstream nodes that depend on this one)
		ArrayNode children = mapper.createArrayNode();
		List<String> childIds = childrenMap.getOrDefault(node.id(), List.of());
		for (String childId : childIds) {
			children.add(childId);
		}
		obj.set("children", children);

		return obj;
	}

	private String resolveNodeType(PipelineNode node) {
		if (node.isSource()) {
			return "source";
		}
		if (isFilterNode(node)) {
			return "filter";
		}
		return "processor";
	}

	private boolean isFilterNode(PipelineNode node) {
		// Check by class name to avoid hard dependency on pipeline-core filter classes
		Class<?> clazz = node.getClass();
		while (clazz != null) {
			if (clazz.getSimpleName().contains("FilterNode") || clazz.getSimpleName().contains("AbstractFilterNode")) {
				return true;
			}
			clazz = clazz.getSuperclass();
		}
		return false;
	}

	/**
	 * Build a map from node id → list of child node ids (nodes that depend on it).
	 */
	private Map<String, List<String>> buildChildrenMap(List<PipelineNode> nodes) {
		Map<String, List<String>> childrenMap = new LinkedHashMap<>();
		for (PipelineNode node : nodes) {
			for (String dep : node.dependencies()) {
				childrenMap.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id());
			}
		}
		return childrenMap;
	}

	/**
	 * Build the tree structure showing roots and branches.
	 */
	private ObjectNode buildTreeStructure(List<PipelineNode> nodes, Map<String, List<String>> childrenMap) {
		ObjectNode tree = mapper.createObjectNode();

		// Roots are nodes with no dependencies
		Set<String> allNodeIds = nodes.stream().map(PipelineNode::id).collect(Collectors.toSet());
		ArrayNode roots = mapper.createArrayNode();
		for (PipelineNode node : nodes) {
			// A root node has no dependencies (or all deps are outside this pipeline)
			boolean isRoot = node.dependencies().stream().noneMatch(allNodeIds::contains);
			if (isRoot) {
				roots.add(node.id());
			}
		}
		tree.set("roots", roots);

		// Branches: for each node that has children, list the children
		ObjectNode branches = mapper.createObjectNode();
		for (Map.Entry<String, List<String>> entry : childrenMap.entrySet()) {
			if (allNodeIds.contains(entry.getKey())) {
				ArrayNode branchChildren = mapper.createArrayNode();
				for (String childId : entry.getValue()) {
					branchChildren.add(childId);
				}
				branches.set(entry.getKey(), branchChildren);
			}
		}
		tree.set("branches", branches);

		return tree;
	}
}
