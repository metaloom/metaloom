package io.metaloom.loom.rest.validation;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Check a set of node options against the parameters the node declares.
 *
 * <p>
 * Nothing validated options before this. A pipeline definition is written by the editor, whose form
 * is generated from the very {@link NodeParameter} list checked here, so an out-of-range value was
 * not reachable in practice. Re-execution changes that: it takes settings straight from a request
 * and hands them to a worker that will try to run with them, so a typo becomes a node failure — or,
 * worse, a silently ignored option and an operator concluding the setting does nothing.
 * </p>
 *
 * <p>
 * Deliberately lenient about <em>which</em> options are present. A caller sending one key is
 * changing one setting, and demanding the whole set would make a patch impossible. What it is strict
 * about is any key it does recognise, and any key it does not recognise at all.
 * </p>
 */
public final class NodeOptionValidator {

	private NodeOptionValidator() {
	}

	/**
	 * @param descriptor the node's contract, or null when the node kind is unknown to this Loom
	 * @param options    the options to check; null or empty always passes
	 * @throws ValidationException naming the offending key and why it was rejected
	 */
	public static void validate(NodeDescriptor descriptor, Map<String, Object> options) {
		if (options == null || options.isEmpty()) {
			return;
		}
		if (descriptor == null || descriptor.getParameters() == null) {
			// A node kind announced by a worker this Loom has never seen, or one that declares no
			// parameters at all. Refusing here would make the feature unusable against a third-party
			// node the moment its worker reconnected, so pass the options through and let the node
			// itself be the authority on what it accepts.
			return;
		}

		for (Map.Entry<String, Object> entry : options.entrySet()) {
			NodeParameter parameter = find(descriptor, entry.getKey());
			if (parameter == null) {
				throw new ValidationException("Node '" + descriptor.getName() + "' has no parameter '"
					+ entry.getKey() + "'.");
			}
			checkValue(parameter, entry.getValue());
		}
	}

	private static NodeParameter find(NodeDescriptor descriptor, String key) {
		for (NodeParameter parameter : descriptor.getParameters()) {
			if (parameter.getKey() != null && parameter.getKey().equals(key)) {
				return parameter;
			}
		}
		return null;
	}

	private static void checkValue(NodeParameter parameter, Object value) {
		if (value == null) {
			// Explicitly clearing a setting is legitimate; the node falls back to its default.
			return;
		}
		ParameterType type = parameter.getType();
		if (type == null) {
			return;
		}
		switch (type) {
			case INTEGER -> checkNumber(parameter, value, true);
			case NUMBER -> checkNumber(parameter, value, false);
			case BOOLEAN -> {
				if (!(value instanceof Boolean)) {
					throw typeMismatch(parameter, value, "a boolean");
				}
			}
			case ENUM -> checkEnum(parameter, value);
			case ENUM_SET -> {
				if (!(value instanceof List<?> list)) {
					throw typeMismatch(parameter, value, "a list of values");
				}
				list.forEach(element -> checkEnum(parameter, element));
			}
			// STRING, CODE, JSON and the row-editor types carry free-form content whose shape the
			// node itself owns. Type-checking them here would only duplicate — and eventually
			// contradict — the parsing the node has to do anyway.
			default -> {
			}
		}
	}

	private static void checkNumber(NodeParameter parameter, Object value, boolean integral) {
		if (!(value instanceof Number number)) {
			throw typeMismatch(parameter, value, integral ? "a whole number" : "a number");
		}
		if (integral && number.doubleValue() != Math.rint(number.doubleValue())) {
			throw typeMismatch(parameter, value, "a whole number");
		}
		double actual = number.doubleValue();
		if (parameter.getMin() != null && actual < parameter.getMin().doubleValue()) {
			throw new ValidationException("Parameter '" + parameter.getKey() + "' must be at least "
				+ parameter.getMin() + " but was " + value + ".");
		}
		if (parameter.getMax() != null && actual > parameter.getMax().doubleValue()) {
			throw new ValidationException("Parameter '" + parameter.getKey() + "' must be at most "
				+ parameter.getMax() + " but was " + value + ".");
		}
	}

	private static void checkEnum(NodeParameter parameter, Object value) {
		List<String> allowed = parameter.getValues();
		if (allowed == null || allowed.isEmpty()) {
			return;
		}
		if (!(value instanceof String text) || !allowed.contains(text)) {
			throw new ValidationException("Parameter '" + parameter.getKey() + "' must be one of "
				+ String.join(", ", allowed) + " but was " + value + ".");
		}
	}

	private static ValidationException typeMismatch(NodeParameter parameter, Object value, String expected) {
		return new ValidationException("Parameter '" + parameter.getKey() + "' expects " + expected
			+ " but was '" + value + "'.");
	}
}
