package io.metaloom.cli.client;

/**
 * A failure that already knows how the process should exit.
 *
 * <p>Commands throw this instead of printing and returning a code, so the exit-code policy
 * lives in one place ({@code ClientErrors}) and the top-level exception handler can render
 * every failure the same way.</p>
 */
public class CliException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int exitCode;
	private final String detail;

	public CliException(int exitCode, String message) {
		this(exitCode, message, null, null);
	}

	public CliException(int exitCode, String message, String detail) {
		this(exitCode, message, detail, null);
	}

	public CliException(int exitCode, String message, String detail, Throwable cause) {
		super(message, cause);
		this.exitCode = exitCode;
		this.detail = detail;
	}

	public int getExitCode() {
		return exitCode;
	}

	/** Extra context shown only under {@code -v}, typically the raw server response body. */
	public String getDetail() {
		return detail;
	}
}
