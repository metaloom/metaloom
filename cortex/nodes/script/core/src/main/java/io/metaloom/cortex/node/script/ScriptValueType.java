package io.metaloom.cortex.node.script;

/**
 * The type of a value a script may emit.
 *
 * <p>
 * A script node declares its outputs as {@code {key, type}} pairs in its configuration, and this
 * enum is the {@code type} half. The declaration is configuration rather than something
 * discovered at runtime for a structural reason: the pipeline editor has to draw output handles
 * and the author has to be able to connect a downstream node to them <em>before</em> the script
 * has ever run. A node whose outputs only exist after execution would be unconnectable.
 * </p>
 *
 * <p>
 * Each constant fixes three things: the Java type that ends up in the {@code NodeResult} output
 * map, the content type downstream connectors see, and where the value is persisted.
 * </p>
 */
public enum ScriptValueType {

	/** A short string. */
	STRING("data/string", false),

	/** A longer body of text. Distinguished from {@link #STRING} only for connector typing. */
	TEXT("data/text", false),

	/** A whole number, emitted as {@code Long}. */
	INTEGER("data/integer", false),

	/** A floating-point number, emitted as {@code Double}. */
	NUMBER("data/number", false),

	/** A boolean. */
	BOOLEAN("data/boolean", false),

	/** An arbitrary JSON object, emitted as {@code JsonObject}. */
	JSON("data/text", false),

	/** A list of strings, emitted as {@code List<String>}. */
	TEXT_LIST("data/text", false),

	/**
	 * A time-ranged segment list: {@code [{startMs, endMs, label, data}, ...]}. Persisted as real
	 * rows in {@code asset_segment_comp} rather than as an opaque blob, so the timeline is
	 * queryable and the UI can render it.
	 */
	TIMEFRAMES("data/scene", false),

	/** A single generated image. The emitted value is the path the bytes were written to. */
	IMAGE("data/thumbnail", true),

	/** Several generated images. The emitted value is the list of paths. */
	IMAGE_LIST("data/thumbnail", true),

	/** A filesystem path. */
	PATH("data/path", false);

	private final String contentType;
	private final boolean binary;

	ScriptValueType(String contentType, boolean binary) {
		this.contentType = contentType;
		this.binary = binary;
	}

	/**
	 * The {@code ContentTypes} id downstream connectors see for a value of this type.
	 */
	public String contentType() {
		return contentType;
	}

	/**
	 * Whether values of this type carry bytes that are written to the local binary cache rather
	 * than being persisted into Loom. There is no byte-ingest endpoint for produced media, so
	 * these stay on the worker and only the ledger records that they were produced.
	 */
	public boolean isBinary() {
		return binary;
	}

	/**
	 * Whether values of this type are persisted into the node's JSON component. Binary and
	 * segment values have their own landing zones.
	 */
	public boolean isJsonPayload() {
		return !binary && this != TIMEFRAMES;
	}

	/**
	 * Parse a declared type name, case-insensitively.
	 *
	 * @throws IllegalArgumentException naming the valid values, because this message is shown to
	 *                                  a pipeline author who mistyped a type in the editor
	 */
	public static ScriptValueType parse(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("output type must not be empty; expected one of " + names());
		}
		for (ScriptValueType type : values()) {
			if (type.name().equalsIgnoreCase(value.trim())) {
				return type;
			}
		}
		throw new IllegalArgumentException("unknown output type '" + value + "'; expected one of " + names());
	}

	private static String names() {
		StringBuilder builder = new StringBuilder();
		for (ScriptValueType type : values()) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(type.name());
		}
		return builder.toString();
	}
}
