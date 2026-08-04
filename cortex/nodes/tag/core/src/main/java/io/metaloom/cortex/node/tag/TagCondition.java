package io.metaloom.cortex.node.tag;

import io.vertx.core.json.JsonObject;

/**
 * One predicate of a {@link TagRule}: <em>the value on port {@code input} (at {@code path}) {@code op}
 * {@code value}</em>.
 *
 * <p>
 * {@code input} names a <strong>port id</strong> — {@code text}, {@code number}, {@code flag},
 * {@code struct}, {@code labels} — never an upstream node. Where the data comes from is an edge the
 * pipeline author drew, and a rule that could name a node id would smuggle the deleted
 * {@code "nodeId:outputKey"} option back in through a JSON field.
 * </p>
 *
 * @param input the port id to read, or null inside a {@code forEach} rule, where the subject is the
 *              element being iterated
 * @param path  dot path into a {@code struct} value ({@code "image.width"}, {@code "colors.0.name"});
 *              ignored for the scalar ports
 * @param op    the comparison
 * @param value the literal to compare against; absent for {@link TagOp#EXISTS} / {@link TagOp#NOT_BLANK}
 */
public record TagCondition(String input, String path, TagOp op, Object value) {

	/**
	 * Parse one condition row. An unparseable {@code op} yields a condition with a null operator rather
	 * than throwing, so {@link #validate()} can report every problem in the rule set at once instead of
	 * failing on the first.
	 */
	public static TagCondition from(JsonObject json) {
		TagOp op = null;
		String raw = json.getString("op");
		if (raw != null) {
			try {
				op = TagOp.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				op = null;
			}
		}
		return new TagCondition(trimmed(json.getString("input")), trimmed(json.getString("path")), op, json.getValue("value"));
	}

	/** The reason this condition cannot run, or null when it is fine. */
	public String validate() {
		if (op == null) {
			return "unknown or missing op; expected one of " + java.util.Arrays.toString(TagOp.values());
		}
		if (input != null && !TagInputs.PORT_IDS.contains(input)) {
			return "unknown input '" + input + "'; expected one of " + TagInputs.PORT_IDS;
		}
		return op.validate(value);
	}

	/**
	 * Evaluate against the wired inputs.
	 *
	 * @param inputs  the snapshot of what the ports carry for this item
	 * @param element the current element of a {@code forEach} rule, or null outside one
	 */
	public boolean matches(TagInputs inputs, Object element) {
		if (op == null) {
			return false;
		}
		Object subject = input == null ? element : inputs.value(input, path);
		return op.test(subject, value);
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
