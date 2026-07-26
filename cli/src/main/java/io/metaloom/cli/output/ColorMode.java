package io.metaloom.cli.output;

public enum ColorMode {

	/** Colour when stdout looks like a terminal. */
	AUTO,
	ALWAYS,
	NEVER;

	public static ColorMode parse(String value) {
		if (value == null || value.isBlank()) {
			return AUTO;
		}
		return switch (value.trim().toLowerCase()) {
			case "auto" -> AUTO;
			case "always", "yes", "true" -> ALWAYS;
			case "never", "no", "false" -> NEVER;
			default -> throw new IllegalArgumentException(
				"Unknown color mode '" + value + "'. Expected one of: auto, always, never.");
		};
	}
}
