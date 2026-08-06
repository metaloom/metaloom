package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: list_node_descriptors
 *
 * <p>
 * The palette an agent designs a pipeline from. Which node kinds exist is a property of the deployment — a built-in set compiled into this Loom plus
 * whatever the connected Cortex workers announced — so a definition written from memory names kinds this Loom may not know, and
 * {@code PipelineGraphParser} rejects an unknown kind outright.
 * </p>
 *
 * <p>
 * 🔴 <b>Projected, never dumped.</b> The full descriptor set that {@code NodeDescriptorEndpoint} serves the editor is ~115 KB for 34 nodes; handing
 * that to a model would spend most of a context window on JSON it cannot act on. One line per kind is enough to choose, and
 * {@link GetNodeDescriptorTool} answers the follow-up question about the one kind that was chosen — the same reason {@code list_pipelines} omits node
 * graphs.
 * </p>
 */
@Singleton
public class ListNodeDescriptorsTool implements MCPTool {

	/** Enough to see the whole palette of a normal deployment in one call, small enough to stay readable. */
	private static final int DEFAULT_LIMIT = 100;

	/** Derived rather than spelled out, so a new category cannot become unfilterable without anyone noticing. */
	private static final List<String> CATEGORY_NAMES = Stream.of(NodeCategory.values()).map(c -> c.name()).toList();

	private final NodeDescriptorRegistry registry;

	@Inject
	public ListNodeDescriptorsTool(NodeDescriptorRegistry registry) {
		this.registry = registry;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"list_node_descriptors",
			"List the pipeline node kinds available in this Loom, with their category and a one-line description. "
				+ "Call this FIRST when designing or changing a pipeline: the available kinds differ per deployment and a definition "
				+ "referencing an unknown kind is rejected. Use get_node_descriptor afterwards for the exact port ids and options of "
				+ "each kind you intend to use.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("category", "string", "Only kinds in this category", false, CATEGORY_NAMES),
				new MCPToolParam("query", "string", "Case-insensitive substring matched against the kind, name and description", false),
				new MCPToolParam("includePorts", "boolean", "Append a compact summary of each kind's input and output ports (default false)", false),
				new MCPToolParam("limit", "integer", "Maximum number of kinds to return (default " + DEFAULT_LIMIT + ")", false))),
			List.of("READ_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String category = trimmedUpper(arguments.getString("category"));
			String query = trimmedLower(arguments.getString("query"));
			boolean includePorts = arguments.getBoolean("includePorts", false);
			int limit = arguments.getInteger("limit", DEFAULT_LIMIT);
			if (limit < 1) {
				limit = DEFAULT_LIMIT;
			}

			List<NodeDescriptor> matched = new ArrayList<>();
			for (NodeDescriptor descriptor : registry.getAll()) {
				if (category != null && (descriptor.getCategory() == null || !descriptor.getCategory().name().equals(category))) {
					continue;
				}
				if (query != null && !matches(descriptor, query)) {
					continue;
				}
				matched.add(descriptor);
			}

			StringBuilder sb = new StringBuilder();
			int shown = Math.min(matched.size(), limit);
			sb.append("Found ").append(matched.size()).append(" node kind").append(matched.size() == 1 ? "" : "s");
			if (shown < matched.size()) {
				sb.append(", showing the first ").append(shown).append(" — narrow with category or query rather than raising the limit");
			}
			sb.append(".\n\n");

			for (int i = 0; i < shown; i++) {
				sb.append(line(matched.get(i), includePorts)).append("\n");
			}
			if (!includePorts && shown > 0) {
				sb.append("\nCall get_node_descriptor for a kind to see its port ids, content types and options.\n");
			}
			return Future.succeededFuture(mcpTextResult(sb.toString()));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	private static boolean matches(NodeDescriptor descriptor, String query) {
		return contains(descriptor.getNodeId(), query)
			|| contains(descriptor.getName(), query)
			|| contains(descriptor.getDescription(), query);
	}

	private static boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query);
	}

	/**
	 * One kind, one line: {@code - facedetect [ANALYSIS] Face Detection — Detect faces in an image or video.}
	 */
	private static String line(NodeDescriptor descriptor, boolean includePorts) {
		StringBuilder sb = new StringBuilder();
		sb.append("- ").append(descriptor.getNodeId())
			.append(" [").append(descriptor.getCategory() == null ? "ANALYSIS" : descriptor.getCategory().name()).append("]");
		if (descriptor.getName() != null) {
			sb.append(" ").append(descriptor.getName());
		}
		if (descriptor.getDescription() != null && !descriptor.getDescription().isBlank()) {
			sb.append(" — ").append(oneLine(descriptor.getDescription()));
		}
		if (includePorts) {
			sb.append("\n    in: ").append(ports(descriptor.getInputPorts()));
			sb.append("\n    out: ").append(ports(descriptor.getOutputPorts()));
			if (descriptor.isDynamicPorts()) {
				// A static listing cannot tell the truth about script/llm/vlm/filter: their real ports
				// come from the instance options, so pointing at the resolver is the only honest answer.
				sb.append("\n    (dynamic ports — call get_node_descriptor with this kind's options to see the real ones)");
			}
		}
		return sb.toString();
	}

	private static String ports(List<PortSpec> ports) {
		if (ports == null || ports.isEmpty()) {
			return "—";
		}
		StringBuilder sb = new StringBuilder();
		for (PortSpec port : ports) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(port.getId()).append(" (").append(port.getContentType());
			if (port.getCardinality() == Cardinality.MANY) {
				// Worth the extra characters: a MANY output is what makes a downstream node run
				// per element, so it changes the shape of the graph an author writes.
				sb.append(", MANY");
			}
			sb.append(")");
		}
		return sb.toString();
	}

	/** Descriptions are authored as prose and some wrap; a listing line must stay a line. */
	private static String oneLine(String value) {
		return value.replaceAll("\\s+", " ").trim();
	}

	private static String trimmedUpper(String value) {
		return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private static String trimmedLower(String value) {
		return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
	}

}
