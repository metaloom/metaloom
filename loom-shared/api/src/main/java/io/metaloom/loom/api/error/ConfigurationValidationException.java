package io.metaloom.loom.api.error;

import java.util.List;

/**
 * Thrown when the loaded configuration fails validation. The exception carries <b>all</b> detected problems instead of only the first one so that a
 * user can fix the whole configuration in one go.
 */
public class ConfigurationValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> errors;

	/**
	 * Create a new exception listing every detected configuration problem.
	 *
	 * @param errors
	 *            the collected validation errors
	 */
	public ConfigurationValidationException(List<String> errors) {
		super(buildMessage(errors));
		this.errors = List.copyOf(errors);
	}

	/**
	 * Return the individual validation errors. Each entry is prefixed with the dotted path of the offending setting.
	 *
	 * @return unmodifiable list of errors
	 */
	public List<String> getErrors() {
		return errors;
	}

	private static String buildMessage(List<String> errors) {
		StringBuilder builder = new StringBuilder();
		builder.append("Configuration validation failed with ").append(errors.size()).append(" error(s):");
		for (String error : errors) {
			builder.append(System.lineSeparator()).append("  - ").append(error);
		}
		return builder.toString();
	}
}
