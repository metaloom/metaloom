package io.metaloom.cortex.node.script.engine;

/**
 * The resource envelope a script runs inside.
 *
 * <p>
 * These apply in <em>both</em> trust modes. A trusted script is trusted not to be malicious; it
 * is not trusted to be free of infinite loops, and a node that never returns holds a worker slot
 * forever.
 * </p>
 *
 * @param trusted         when false, the engine denies host access, class lookup, threads and IO
 * @param allowNetwork    expose the {@code http} binding
 * @param allowFilesystem expose the read-only {@code fs} binding
 * @param timeoutMs       wall-clock budget for one execution
 * @param statementLimit  guest-statement budget for one execution; the portable guard against a
 *                        tight loop that a wall clock alone would only catch after the fact
 * @param maxLogLines     cap on {@code log.*} calls, so a script cannot flood the worker log
 */
public record ScriptLimits(
	boolean trusted,
	boolean allowNetwork,
	boolean allowFilesystem,
	long timeoutMs,
	long statementLimit,
	int maxLogLines) {

	public ScriptLimits {
		if (timeoutMs <= 0) {
			throw new IllegalArgumentException("timeoutMs must be positive, got " + timeoutMs);
		}
		if (statementLimit <= 0) {
			throw new IllegalArgumentException("statementLimit must be positive, got " + statementLimit);
		}
		if (maxLogLines < 0) {
			throw new IllegalArgumentException("maxLogLines must not be negative, got " + maxLogLines);
		}
	}

	/** Trusted defaults, for tests and for a node built without explicit limits. */
	public static ScriptLimits defaults() {
		return new ScriptLimits(true, false, false, 10_000L, 10_000_000L, 200);
	}
}
