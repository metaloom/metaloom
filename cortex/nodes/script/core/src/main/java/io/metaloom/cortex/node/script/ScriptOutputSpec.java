package io.metaloom.cortex.node.script;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.metaloom.cortex.api.node.OutputPort;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * One declared output of a script node: the key it is emitted under, the type of value it carries,
 * and - for timeframe outputs - the segment type its rows are stored under.
 *
 * @param key         the output key, also the key in the {@code NodeResult} output map
 * @param type        the value type
 * @param segmentType for {@link ScriptValueType#TIMEFRAMES}, the {@code asset_segment_comp}
 *                    segment type; null for every other type
 */
public record ScriptOutputSpec(String key, ScriptValueType type, String segmentType) {

	/**
	 * The output port this declaration becomes.
	 *
	 * <p>
	 * The value type is {@code Object} because a script's values are only known by their declared
	 * {@code ScriptValueType}; the content type is what actually constrains them, and the boundary
	 * coercer enforces it.
	 * </p>
	 */
	public OutputPort<Object> port() {
		return type.isList()
			? OutputPort.many(key, type.contentType(), Object.class)
			: OutputPort.one(key, type.contentType(), Object.class);
	}

	/**
	 * Output keys are port ids, so they share the shape every other port id has - the editor draws
	 * a handle for each and an edge references it by name.
	 */
	public static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_]{0,62}$");

	/**
	 * The segment types the database accepts.
	 *
	 * <p>
	 * ⚠️ {@code asset_segment_comp} constrains {@code segment_type} with a CHECK to exactly this
	 * set (migration {@code V2.42__add_asset_segment_comp.sql}), so a timeframe output cannot
	 * simply be stored under its own key - the insert would be rejected by the database. A script
	 * therefore picks one of these for each timeframe output. Widening the set is a Flyway change
	 * and deliberately out of this node's scope.
	 * </p>
	 */
	public static final List<String> SEGMENT_TYPES = List.of("SCENE", "SILENCE", "SHOT", "CHAPTER");

	/** What a timeframe output gets when it does not say: the closest thing to "a mark on a timeline". */
	public static final String DEFAULT_SEGMENT_TYPE = "CHAPTER";

	public ScriptOutputSpec {
		if (key == null || !KEY_PATTERN.matcher(key).matches()) {
			throw new IllegalArgumentException("output key '" + key + "' must match " + KEY_PATTERN.pattern());
		}
		if (type == null) {
			throw new IllegalArgumentException("output '" + key + "' has no type");
		}
		if (type == ScriptValueType.TIMEFRAMES) {
			segmentType = segmentType == null || segmentType.isBlank() ? DEFAULT_SEGMENT_TYPE : segmentType.trim().toUpperCase();
			if (!SEGMENT_TYPES.contains(segmentType)) {
				throw new IllegalArgumentException("output '" + key + "' has segmentType '" + segmentType
					+ "'; the database accepts only " + SEGMENT_TYPES);
			}
		} else if (segmentType != null) {
			throw new IllegalArgumentException("output '" + key + "' is " + type + ", so segmentType does not apply");
		}
	}

	/** Convenience for the common non-timeframe case. */
	public ScriptOutputSpec(String key, ScriptValueType type) {
		this(key, type, null);
	}

	/**
	 * Parse the {@code outputs} declaration: an array of {@code {"key": ..., "type": ...}}.
	 *
	 * @throws IllegalArgumentException on a malformed entry, an unknown type, or a duplicate key.
	 *                                  Duplicates are rejected rather than last-one-wins because
	 *                                  the editor would draw two identical handles and the author
	 *                                  could not tell which one an edge attached to
	 */
	public static List<ScriptOutputSpec> parse(JsonArray declared) {
		List<ScriptOutputSpec> specs = new ArrayList<>();
		if (declared == null) {
			return specs;
		}
		Set<String> seen = new LinkedHashSet<>();
		Set<String> segmentTypes = new LinkedHashSet<>();
		for (int i = 0; i < declared.size(); i++) {
			Object raw = declared.getValue(i);
			if (!(raw instanceof JsonObject entry)) {
				throw new IllegalArgumentException("outputs[" + i + "] must be an object with 'key' and 'type'");
			}
			String key = entry.getString("key");
			ScriptValueType type = ScriptValueType.parse(entry.getString("type"));
			ScriptOutputSpec spec = new ScriptOutputSpec(key, type,
				type == ScriptValueType.TIMEFRAMES ? entry.getString("segmentType") : null);
			if (!seen.add(spec.key())) {
				throw new IllegalArgumentException("duplicate output key '" + spec.key() + "'");
			}
			// A segment write replaces the whole set for its type, so two timeframe outputs on one
			// node sharing a type would silently wipe each other.
			if (spec.segmentType() != null && !segmentTypes.add(spec.segmentType())) {
				throw new IllegalArgumentException("two timeframe outputs both use segmentType '" + spec.segmentType()
					+ "'; each needs a distinct one from " + SEGMENT_TYPES);
			}
			specs.add(spec);
		}
		return specs;
	}
}
