package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * One row of the {@code rules} option: <em>when these conditions hold, attach this tag</em>.
 *
 * <p>
 * Rows are independent — several may fire for one item, which is the difference between tagging and
 * the {@code filter} node's single bucket. A row either names a fixed {@link #tag()} or, inside a
 * {@link #forEach()} iteration, builds one from {@link #tagTemplate()}.
 * </p>
 *
 * @param id          stable identifier, recorded on every applied tag so a later run can tell which
 *                    rule put it there
 * @param tag         the tag name, or null when {@code tagTemplate} is used
 * @param tagTemplate a name built per element, with {@code ${value}} standing for the element
 * @param collection  the collection to write into, or null to fall back to the node's option
 * @param match       ALL (default) or ANY over {@link #when()}
 * @param forEach     a MANY port id to iterate ({@code labels}), or null to evaluate once per item
 * @param when        the conditions; an empty list never fires
 */
public record TagRule(String id, String tag, String tagTemplate, String collection, Match match, String forEach, List<TagCondition> when) {

	/** How the conditions of a rule combine. */
	public enum Match {
		ALL,
		ANY
	}

	/** The placeholder a {@code tagTemplate} uses for the element being iterated. */
	public static final String VALUE_PLACEHOLDER = "${value}";

	public static TagRule from(JsonObject json) {
		List<TagCondition> conditions = new ArrayList<>();
		JsonArray when = json.getJsonArray("when");
		if (when != null) {
			for (Object entry : when) {
				if (entry instanceof JsonObject condition) {
					conditions.add(TagCondition.from(condition));
				}
			}
		}
		Match match = Match.ALL;
		String rawMatch = json.getString("match");
		if (rawMatch != null && rawMatch.trim().equalsIgnoreCase("any")) {
			match = Match.ANY;
		}
		return new TagRule(
			trimmed(json.getString("id")),
			trimmed(json.getString("tag")),
			trimmed(json.getString("tagTemplate")),
			trimmed(json.getString("collection")),
			match,
			trimmed(json.getString("forEach")),
			conditions);
	}

	/** Every reason this rule cannot run; empty when it is fine. */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		String where = "rule '" + (id == null ? "?" : id) + "'";
		if (id == null) {
			errors.add("every rule needs an id");
		}
		if (tag == null && tagTemplate == null) {
			errors.add(where + " needs a tag or a tagTemplate");
		}
		if (tag != null && tagTemplate != null) {
			errors.add(where + " sets both tag and tagTemplate; pick one");
		}
		if (tagTemplate != null && forEach == null) {
			errors.add(where + " uses a tagTemplate but no forEach, so there is no value to build a name from");
		}
		if (forEach != null && !TagInputs.MANY_PORT_IDS.contains(forEach)) {
			errors.add(where + " iterates '" + forEach + "', which is not one of the list ports " + TagInputs.MANY_PORT_IDS);
		}
		if (when.isEmpty()) {
			errors.add(where + " has no conditions, so it can never fire");
		}
		for (TagCondition condition : when) {
			String error = condition.validate();
			if (error != null) {
				errors.add(where + ": " + error);
			}
		}
		return errors;
	}

	/**
	 * Whether this rule fires for the given inputs.
	 *
	 * @param element the current element inside a {@code forEach} iteration, or null
	 */
	public boolean matches(TagInputs inputs, Object element) {
		if (when.isEmpty()) {
			return false;
		}
		for (TagCondition condition : when) {
			boolean hit = condition.matches(inputs, element);
			if (match == Match.ALL && !hit) {
				return false;
			}
			if (match == Match.ANY && hit) {
				return true;
			}
		}
		return match == Match.ALL;
	}

	/** The tag name this rule produces for the given element, before normalisation. */
	public String nameFor(Object element) {
		if (tagTemplate == null) {
			return tag;
		}
		return tagTemplate.replace(VALUE_PLACEHOLDER, element == null ? "" : String.valueOf(element));
	}

	/**
	 * The ports this rule reads, so the node can say which of them were left unwired rather than
	 * silently producing nothing.
	 */
	public List<String> referencedPorts() {
		List<String> ports = new ArrayList<>();
		if (forEach != null) {
			ports.add(forEach);
		}
		for (TagCondition condition : when) {
			if (condition.input() != null && !ports.contains(condition.input())) {
				ports.add(condition.input());
			}
		}
		return ports;
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
