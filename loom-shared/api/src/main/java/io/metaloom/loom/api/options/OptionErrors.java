package io.metaloom.loom.api.options;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.metaloom.loom.api.error.ConfigurationValidationException;

/**
 * Collector for configuration validation errors.
 * <p>
 * All {@link Option} implementations validate into a shared collector so that every problem in the configuration is reported at once instead of
 * failing on the first one. Errors are prefixed with the dotted path of the setting (e.g. {@code auth.oauth2.clientId}) and, where known, with the
 * name of the environment variable that can be used to override it.
 * <p>
 * Typical usage:
 *
 * <pre>
 * OptionErrors errors = new OptionErrors();
 * errors.nested("database", database).nested("server", server);
 * errors.throwOnError();
 * </pre>
 */
public final class OptionErrors {

	/**
	 * Hostnames as well as IPv4/IPv6 literals. Deliberately lenient - this is a syntax check, no name resolution is performed.
	 */
	private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\[\\]-]+$");

	private static final int MIN_PORT = 1;

	private static final int MAX_PORT = 65535;

	private final List<String> errors;

	private final String path;

	private final Class<?> owner;

	/**
	 * Create a new root collector.
	 */
	public OptionErrors() {
		this(new ArrayList<>(), "", null);
	}

	private OptionErrors(List<String> errors, String path, Class<?> owner) {
		this.errors = errors;
		this.path = path;
		this.owner = owner;
	}

	/**
	 * Validate the given sub option under the provided name. The sub option reports into the same collector but its errors are prefixed with the
	 * nested path. A {@code null} sub option is reported as an error instead of being silently skipped.
	 *
	 * @param name
	 *            name of the sub option as used in the YAML document
	 * @param option
	 *            the sub option to validate
	 * @return fluent self reference
	 */
	public OptionErrors nested(String name, Option option) {
		if (option == null) {
			return add(name, "must not be null");
		}
		option.validate(new OptionErrors(errors, join(name), option.getClass()));
		return this;
	}

	/**
	 * Record a validation error for the given field.
	 *
	 * @param field
	 *            name of the offending field
	 * @param message
	 *            description of the problem
	 * @return fluent self reference
	 */
	public OptionErrors add(String field, String message) {
		String envName = OptionUtils.envVarNameFor(owner, field);
		String hint = envName == null ? "" : " [env: " + envName + "]";
		errors.add(join(field) + ": " + message + hint);
		return this;
	}

	/**
	 * Assert that the value is neither {@code null} nor blank. The value itself is never included in the message so that secrets are not leaked.
	 */
	public OptionErrors notBlank(String field, String value) {
		if (value == null || value.isBlank()) {
			return add(field, "must not be empty");
		}
		return this;
	}

	/**
	 * Assert that the value is a usable TCP port.
	 */
	public OptionErrors port(String field, int value) {
		if (value < MIN_PORT || value > MAX_PORT) {
			return add(field, "must be a port between " + MIN_PORT + " and " + MAX_PORT + " but was " + value);
		}
		return this;
	}

	/**
	 * Assert that the value is greater than or equal to the given minimum.
	 */
	public OptionErrors min(String field, int value, int min) {
		if (value < min) {
			return add(field, "must be at least " + min + " but was " + value);
		}
		return this;
	}

	/**
	 * Assert that the value is a syntactically valid hostname or IP literal.
	 */
	public OptionErrors host(String field, String value) {
		if (value == null || value.isBlank()) {
			return add(field, "must not be empty");
		}
		if (!HOST_PATTERN.matcher(value).matches()) {
			return add(field, "must be a valid hostname or IP address but was '" + value + "'");
		}
		return this;
	}

	/**
	 * Assert that the value is an absolute http(s) URL.
	 */
	public OptionErrors url(String field, String value) {
		if (value == null || value.isBlank()) {
			return add(field, "must not be empty");
		}
		URI uri;
		try {
			uri = new URI(value);
		} catch (Exception e) {
			return add(field, "must be a valid URL but was '" + value + "'");
		}
		if (!uri.isAbsolute() || uri.getHost() == null) {
			return add(field, "must be an absolute URL including scheme and host but was '" + value + "'");
		}
		String scheme = uri.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return add(field, "must use the http or https scheme but was '" + scheme + "'");
		}
		return this;
	}

	/**
	 * @return true when no error has been recorded so far
	 */
	public boolean isEmpty() {
		return errors.isEmpty();
	}

	/**
	 * Return all recorded errors across the whole option tree.
	 */
	public List<String> errors() {
		return List.copyOf(errors);
	}

	/**
	 * Throw a {@link ConfigurationValidationException} containing every recorded error. Does nothing when no error was recorded.
	 */
	public void throwOnError() {
		if (!errors.isEmpty()) {
			throw new ConfigurationValidationException(errors);
		}
	}

	private String join(String field) {
		return path.isEmpty() ? field : path + "." + field;
	}
}
