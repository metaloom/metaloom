package io.metaloom.loom.agent.sandbox.error;

/**
 * Base type for all sandbox failures. A plain {@code SandboxException} signals a non-retryable error
 * (the caller should surface it to the model as a tool error result and continue).
 */
public class SandboxException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SandboxException(String message) {
		super(message);
	}

	public SandboxException(String message, Throwable cause) {
		super(message, cause);
	}
}
