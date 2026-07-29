package io.metaloom.cortex.node.script;

import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

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
 * Each constant fixes four things: the Java type that ends up in the node's output, the content
 * type downstream connectors see, <strong>the cardinality of the port it becomes</strong>, and
 * where the value is persisted.
 * </p>
 *
 * <p>
 * ⚠️ The content type and cardinality here must match {@code ScriptPortResolver.ScriptOutputType}
 * in {@code loom-shared/node-model}, which derives a script node's descriptor ports from the same
 * declarations. The two live in different trees on purpose - the node model cannot depend on a
 * node - and {@code NodePortConformanceTest} is what keeps them in step.
 * </p>
 */
public enum ScriptValueType {

	/** A short string. */
	STRING(ContentTypeRegistry.SCALAR_STRING, Cardinality.ONE, false),

	/** A longer body of text. Distinguished from {@link #STRING} only for connector typing. */
	TEXT(ContentTypeRegistry.TEXT_PLAIN, Cardinality.ONE, false),

	/** A whole number, emitted as {@code Long}. */
	INTEGER(ContentTypeRegistry.SCALAR_INTEGER, Cardinality.ONE, false),

	/** A floating-point number, emitted as {@code Double}. */
	NUMBER(ContentTypeRegistry.SCALAR_NUMBER, Cardinality.ONE, false),

	/** A boolean. */
	BOOLEAN(ContentTypeRegistry.SCALAR_BOOLEAN, Cardinality.ONE, false),

	/** An arbitrary JSON object, emitted as {@code JsonObject}. */
	JSON(ContentTypeRegistry.STRUCT_JSON, Cardinality.ONE, false),

	/**
	 * Several texts, emitted as one element each.
	 *
	 * <p>
	 * This is the canonical fan-out: a downstream node with a {@code ONE} text input runs once per
	 * element. It used to declare the same content type as {@link #TEXT}, which made "I emit N of
	 * these" invisible to both the editor and the engine.
	 * </p>
	 */
	TEXT_LIST(ContentTypeRegistry.TEXT_PLAIN, Cardinality.MANY, false),

	/**
	 * A time-ranged segment list: {@code [{startMs, endMs, label, data}, ...]}. Persisted as real
	 * rows in {@code asset_segment_comp} rather than as an opaque blob, so the timeline is
	 * queryable and the UI can render it.
	 */
	TIMEFRAMES(ContentTypeRegistry.STRUCT_SEGMENTS, Cardinality.ONE, false),

	/** A single generated image. The emitted value is the path the bytes were written to. */
	IMAGE(ContentTypeRegistry.ARTIFACT_IMAGE, Cardinality.ONE, true),

	/** Several generated images, emitted as one path element each. */
	IMAGE_LIST(ContentTypeRegistry.ARTIFACT_IMAGE, Cardinality.MANY, true),

	/** A filesystem path. */
	PATH(ContentTypeRegistry.ARTIFACT_FILE, Cardinality.ONE, false);

	private final String contentType;
	private final Cardinality cardinality;
	private final boolean binary;

	ScriptValueType(String contentType, Cardinality cardinality, boolean binary) {
		this.contentType = contentType;
		this.cardinality = cardinality;
		this.binary = binary;
	}

	/**
	 * The content type downstream connectors see for a value of this type.
	 */
	public String contentType() {
		return contentType;
	}

	/**
	 * Whether this type emits one element or a sequence.
	 */
	public Cardinality cardinality() {
		return cardinality;
	}

	/**
	 * Whether values of this type are a sequence rather than a single element.
	 */
	public boolean isList() {
		return cardinality.isMany();
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
