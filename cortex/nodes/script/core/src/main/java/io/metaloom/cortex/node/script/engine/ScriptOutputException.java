package io.metaloom.cortex.node.script.engine;

/**
 * Raised when a script writes an output the node did not declare, or a value that cannot be
 * coerced to the declared type.
 *
 * <p>
 * Unchecked so it can propagate out of a guest call without the engine having to declare it; the
 * node turns it into a {@code FAILED} result carrying the message.
 * </p>
 */
public class ScriptOutputException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ScriptOutputException(String message) {
		super(message);
	}

	public ScriptOutputException(String message, Throwable cause) {
		super(message, cause);
	}
}
