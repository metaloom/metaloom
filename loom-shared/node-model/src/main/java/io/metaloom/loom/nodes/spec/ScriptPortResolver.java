package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Derives a {@code script} node's output ports from its {@code outputs} option.
 *
 * <p>
 * The option is a JSON array of <code>{"key": ..., "type": ...}</code> objects whose {@code type} is
 * a {@code ScriptValueType} name. This class is the one place where those names are mapped onto the
 * port model, and the mapping fixes the defect the whole refactor exists for: <strong>the list types
 * become {@link Cardinality#MANY}</strong> rather than collapsing onto the same content type as
 * their scalar counterpart. {@code TEXT_LIST} used to declare {@code data/text} exactly like
 * {@code TEXT}, and {@code IMAGE_LIST} {@code data/thumbnail} exactly like {@code IMAGE}, which made
 * "I emit N of these" invisible to the editor and to the engine. A script is therefore the canonical
 * fan-out producer.
 * </p>
 *
 * <p>
 * Nothing here throws. The options come from a pipeline definition an author typed, so a malformed
 * entry must degrade to "this port does not exist" and let save-time validation report the unwired
 * edge - a resolver that threw would take out the whole descriptor listing instead.
 * </p>
 */
public class ScriptPortResolver implements NodePortResolver {

	static final String KIND = "script";

	/** The option holding the output declarations. */
	static final String OPTION_OUTPUTS = "outputs";

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	public List<PortSpec> resolveOutputPorts(NodeDescriptor descriptor, Map<String, Object> options) {
		List<Object> declarations = asList(options == null ? null : options.get(OPTION_OUTPUTS));
		List<PortSpec> ports = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Object declaration : declarations) {
			if (!(declaration instanceof Map<?, ?> entry)) {
				continue;
			}
			String key = asString(entry.get("key"));
			String type = asString(entry.get("type"));
			if (key == null || !key.matches(PortSpec.ID_PATTERN) || !seen.add(key)) {
				continue;
			}
			ScriptOutputType mapped = ScriptOutputType.parse(type);
			if (mapped == null) {
				continue;
			}
			ports.add(new PortSpec(key, mapped.contentType, mapped.cardinality)
				.describedAs(key, "Declared script output of type " + mapped.name()));
		}
		return ports;
	}

	/**
	 * The {@code ScriptValueType} vocabulary, mapped onto content type plus cardinality.
	 *
	 * <p>
	 * Mirrored rather than imported: {@code cortex/nodes/script} depends on this module, not the
	 * other way round. The conformance test in {@code integration-test/} - which sees both trees -
	 * is what keeps the two in step.
	 * </p>
	 */
	enum ScriptOutputType {

		STRING(ContentTypeRegistry.SCALAR_STRING, Cardinality.ONE),
		TEXT(ContentTypeRegistry.TEXT_PLAIN, Cardinality.ONE),
		INTEGER(ContentTypeRegistry.SCALAR_INTEGER, Cardinality.ONE),
		NUMBER(ContentTypeRegistry.SCALAR_NUMBER, Cardinality.ONE),
		BOOLEAN(ContentTypeRegistry.SCALAR_BOOLEAN, Cardinality.ONE),
		JSON(ContentTypeRegistry.STRUCT_JSON, Cardinality.ONE),
		TEXT_LIST(ContentTypeRegistry.TEXT_PLAIN, Cardinality.MANY),
		TIMEFRAMES(ContentTypeRegistry.STRUCT_SEGMENTS, Cardinality.ONE),
		IMAGE(ContentTypeRegistry.ARTIFACT_IMAGE, Cardinality.ONE),
		IMAGE_LIST(ContentTypeRegistry.ARTIFACT_IMAGE, Cardinality.MANY),
		PATH(ContentTypeRegistry.ARTIFACT_FILE, Cardinality.ONE);

		final String contentType;
		final Cardinality cardinality;

		ScriptOutputType(String contentType, Cardinality cardinality) {
			this.contentType = contentType;
			this.cardinality = cardinality;
		}

		/**
		 * Parse a declared type name case-insensitively.
		 *
		 * @return {@code null} for an unknown or absent name - never an exception, see the class javadoc
		 */
		static ScriptOutputType parse(String name) {
			if (name == null) {
				return null;
			}
			try {
				return valueOf(name.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	private static List<Object> asList(Object value) {
		if (value instanceof List<?> list) {
			// Deliberately not List.copyOf: a malformed definition may contain a null element,
			// and rejecting the whole node over it would be worse than skipping the entry.
			return new ArrayList<>(list);
		}
		return List.of();
	}

	private static String asString(Object value) {
		if (value instanceof String text) {
			String trimmed = text.trim();
			return trimmed.isEmpty() ? null : trimmed;
		}
		return null;
	}
}
