package io.metaloom.cli;

/**
 * Process exit codes.
 *
 * <p>Distinct codes per failure class, because the CLI is meant to be scripted: a caller
 * should be able to tell "the server said no" from "I could not reach the server" without
 * parsing stderr.</p>
 *
 * <p>{@link #TIMEOUT} and {@link #INTERRUPTED} follow the shell conventions (124 from
 * {@code timeout(1)}, 130 for SIGINT) so they compose with existing tooling.</p>
 */
public final class ExitCode {

	private ExitCode() {
	}

	public static final int OK = 0;

	/** Bad arguments. Matches {@code picocli.CommandLine.ExitCode.USAGE}. */
	public static final int USAGE = 2;

	/** A local file could not be read or written. */
	public static final int FILE_ERROR = 3;

	/** HTTP 404. */
	public static final int NOT_FOUND = 4;

	/** HTTP 401, or WebSocket close 4401. */
	public static final int AUTH_REQUIRED = 5;

	/** HTTP 403. */
	public static final int FORBIDDEN = 6;

	/** HTTP 409. */
	public static final int CONFLICT = 7;

	/** HTTP 400. */
	public static final int VALIDATION_FAILED = 8;

	/** Anything not covered by a more specific code. */
	public static final int ERROR = 10;

	/** The server could not be reached at all. */
	public static final int CONNECT_ERROR = 15;

	/** HTTP 5xx, including the 503 returned when no processor can serve a run. */
	public static final int SERVER_FAILURE = 20;

	/** {@code --wait} saw the run reach a terminal state other than SUCCESS. */
	public static final int RUN_NOT_SUCCESSFUL = 21;

	/** A {@code --wait} or {@code --wait-timeout} deadline expired. */
	public static final int TIMEOUT = 124;

	/** SIGINT. */
	public static final int INTERRUPTED = 130;
}
