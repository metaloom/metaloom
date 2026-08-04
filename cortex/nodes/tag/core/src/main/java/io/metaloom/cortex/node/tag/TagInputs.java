package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What the wired input ports carry for one item, addressed by port id.
 *
 * <p>
 * Read once per item and then passed to every condition, so a rule set of twenty rows does not walk
 * the context twenty times, and so "was this port wired?" has one answer for the whole evaluation.
 * </p>
 *
 * <p>
 * <strong>An unwired port is a null value, never an error.</strong> Conditions on it are false and
 * the node reports the rule as skipped. A node that refuses to start because an optional port has no
 * edge is harder to diagnose than one that visibly tags nothing.
 * </p>
 */
public class TagInputs {

	private static final Logger log = LoggerFactory.getLogger(TagInputs.class);

	/** Every port id a rule may name. */
	public static final Set<String> PORT_IDS = Set.of("text", "number", "flag", "struct", "labels");

	/** The subset a {@code forEach} rule may iterate. */
	public static final Set<String> MANY_PORT_IDS = Set.of("labels");

	private final String text;

	private final Double number;

	private final Boolean flag;

	private final JsonObject struct;

	private final List<String> labels;

	private TagInputs(String text, Double number, Boolean flag, JsonObject struct, List<String> labels) {
		this.text = text;
		this.number = number;
		this.flag = flag;
		this.struct = struct;
		this.labels = labels;
	}

	/** Snapshot the node's input ports. */
	public static TagInputs of(NodeContext<LoomMedia> ctx) {
		List<String> labels = new ArrayList<>();
		for (Element<String> element : ctx.inputs(TagNode.IN_LABELS)) {
			if (element != null && element.value() != null && !element.value().isBlank()) {
				labels.add(element.value());
			}
		}
		return new TagInputs(
			blankToNull(ctx.optionalInput(TagNode.IN_TEXT).orElse(null)),
			ctx.optionalInput(TagNode.IN_NUMBER).orElse(null),
			ctx.optionalInput(TagNode.IN_FLAG).orElse(null),
			parse(ctx.optionalInput(TagNode.IN_STRUCT).orElse(null)),
			labels);
	}

	/** For tests and strategies that build inputs directly. */
	public static TagInputs of(String text, Double number, Boolean flag, JsonObject struct, List<String> labels) {
		return new TagInputs(blankToNull(text), number, flag, struct, labels == null ? List.of() : labels);
	}

	public List<String> labels() {
		return labels;
	}

	public JsonObject struct() {
		return struct;
	}

	/** Whether the named port carries anything for this item. */
	public boolean isWired(String portId) {
		return switch (portId) {
		case "text" -> text != null;
		case "number" -> number != null;
		case "flag" -> flag != null;
		case "struct" -> struct != null;
		case "labels" -> !labels.isEmpty();
		default -> false;
		};
	}

	/**
	 * The value a condition compares against.
	 *
	 * @param portId one of {@link #PORT_IDS}
	 * @param path   a dot path into the {@code struct} value; ignored elsewhere
	 * @return the value, or null when the port is unwired or the path does not resolve
	 */
	public Object value(String portId, String path) {
		return switch (portId) {
		case "text" -> text;
		case "number" -> number;
		case "flag" -> flag;
		case "struct" -> resolve(struct, path);
		// A condition naming the list port without iterating it tests the list as a whole, which is
		// what makes `{"input": "labels", "op": "CONTAINS", "value": "dog"}` mean what it looks like.
		case "labels" -> labels.isEmpty() ? null : String.join(",", labels);
		default -> null;
		};
	}

	/**
	 * Walk a dot path into a JSON document. A numeric segment indexes an array, so
	 * {@code colors.0.name} reaches into a list without any extra syntax.
	 */
	static Object resolve(JsonObject root, String path) {
		if (root == null) {
			return null;
		}
		if (path == null || path.isBlank()) {
			return root;
		}
		Object current = root;
		for (String segment : path.split("\\.")) {
			if (current instanceof JsonObject object) {
				current = object.getValue(segment);
			} else if (current instanceof JsonArray array) {
				int index = index(segment);
				current = index >= 0 && index < array.size() ? array.getValue(index) : null;
			} else {
				return null;
			}
			if (current == null) {
				return null;
			}
		}
		return current;
	}

	private static int index(String segment) {
		try {
			return Integer.parseInt(segment);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Struct values travel the graph as encoded JSON strings (every {@code struct/*} output in the
	 * palette is an {@code OutputPort<String>}), so the port is parsed here rather than by each rule.
	 * A value that is not an object is treated as an unwired port: a rule cannot address a path into
	 * it, and failing the whole item over one malformed upstream payload would be worse.
	 */
	private static JsonObject parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return new JsonObject(raw);
		} catch (RuntimeException e) {
			log.warn("The struct port does not carry a JSON object, ignoring it: {}", e.getMessage());
			return null;
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
