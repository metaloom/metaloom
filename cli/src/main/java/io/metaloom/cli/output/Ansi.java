package io.metaloom.cli.output;

/**
 * Minimal ANSI colouring, disabled unless the output is really going to a terminal.
 *
 * <p>Honours {@code NO_COLOR} (see https://no-color.org) and {@code TERM=dumb} in addition
 * to the explicit {@code --color} flag. {@code System.console()} is the primary TTY probe -
 * it works in a GraalVM native image - but the {@code TERM} check is kept as a fallback for
 * environments where it misreports.</p>
 */
public final class Ansi {

	private static final String RESET = "[0m";

	private final boolean enabled;

	public Ansi(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Decide whether colour should be used.
	 *
	 * @param mode the requested mode
	 * @param env  environment lookup, injected so the decision is testable
	 * @param tty  whether stdout is a terminal
	 */
	public static Ansi resolve(ColorMode mode, java.util.function.Function<String, String> env, boolean tty) {
		if (mode == ColorMode.NEVER) {
			return new Ansi(false);
		}
		// NO_COLOR is honoured even for --color=always: an explicit environment opt-out from
		// the user running the command beats a default baked into a script.
		String noColor = env.apply("NO_COLOR");
		if (noColor != null && !noColor.isEmpty()) {
			return new Ansi(false);
		}
		if (mode == ColorMode.ALWAYS) {
			return new Ansi(true);
		}
		if ("dumb".equals(env.apply("TERM"))) {
			return new Ansi(false);
		}
		return new Ansi(tty);
	}

	public boolean isEnabled() {
		return enabled;
	}

	private String wrap(String code, String text) {
		return enabled ? code + text + RESET : text;
	}

	public String bold(String text) {
		return wrap("[1m", text);
	}

	public String dim(String text) {
		return wrap("[2m", text);
	}

	public String red(String text) {
		return wrap("[31m", text);
	}

	public String green(String text) {
		return wrap("[32m", text);
	}

	public String yellow(String text) {
		return wrap("[33m", text);
	}

	public String blue(String text) {
		return wrap("[34m", text);
	}

	public String cyan(String text) {
		return wrap("[36m", text);
	}

	/**
	 * Colour a run or node status token.
	 *
	 * @param status e.g. {@code RUNNING}, {@code SUCCESS}, {@code FAILED}
	 */
	public String status(String status) {
		if (status == null) {
			return "";
		}
		return switch (status.toUpperCase()) {
			case "SUCCESS", "COMPLETED", "UP" -> green(status);
			case "FAILED", "ERROR", "DOWN" -> red(status);
			case "PARTIAL", "PAUSED", "CANCELLED", "DEGRADED" -> yellow(status);
			case "RUNNING", "PENDING" -> cyan(status);
			default -> status;
		};
	}
}
