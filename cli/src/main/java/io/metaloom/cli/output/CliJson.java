package io.metaloom.cli.output;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import io.metaloom.loom.rest.json.LoomJson;

/**
 * Serializers for CLI output.
 *
 * <p>All three are derived from {@link LoomJson#mapper}, so the custom handling of Vert.x
 * {@code JsonObject}/{@code JsonArray} and of the hash types is inherited rather than
 * re-declared - the YAML one via {@code copyWith(JsonFactory)}, which carries the registered
 * modules across to a different backing format. Copying instead of mutating keeps CLI-only
 * settings (indentation, key order) off the shared instance.</p>
 *
 * <p>⚠️ Deliberately avoids {@code LoomJson.parse(Buffer, …)} and
 * {@code LoomJson.encodeToBuffer(…)}: those route through {@code BufferInternal} and Netty,
 * which should stay reachable-but-never-executed in the native image.</p>
 */
public final class CliJson {

	private CliJson() {
	}

	private static final ObjectMapper JSON = LoomJson.mapper.copy()
		.enable(SerializationFeature.INDENT_OUTPUT)
		// Stable key order, so `metaloom -o json ... | diff` between two runs is meaningful.
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.setSerializationInclusion(Include.NON_NULL);

	/** Compact single-line JSON, for the NDJSON event stream. */
	private static final ObjectMapper NDJSON = LoomJson.mapper.copy()
		.disable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.setSerializationInclusion(Include.NON_NULL);

	private static final ObjectMapper YAML = LoomJson.mapper
		.copyWith(new YAMLFactory()
			.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
			.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.setSerializationInclusion(Include.NON_NULL);

	public static ObjectMapper json() {
		return JSON;
	}

	public static ObjectMapper ndjson() {
		return NDJSON;
	}

	public static ObjectMapper yaml() {
		return YAML;
	}
}
