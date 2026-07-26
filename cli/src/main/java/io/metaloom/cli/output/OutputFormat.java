package io.metaloom.cli.output;

/**
 * How results are rendered on stdout.
 */
public enum OutputFormat {

	/** Aligned columns for a human at a terminal. */
	TABLE,

	/** Pretty JSON, or newline-delimited JSON when streaming events. */
	JSON,

	/** YAML documents. */
	YAML;

	public static OutputFormat parse(String value) {
		if (value == null || value.isBlank()) {
			return TABLE;
		}
		return switch (value.trim().toLowerCase()) {
			case "table", "text", "human" -> TABLE;
			case "json" -> JSON;
			case "yaml", "yml" -> YAML;
			default -> throw new IllegalArgumentException(
				"Unknown output format '" + value + "'. Expected one of: table, json, yaml.");
		};
	}
}
