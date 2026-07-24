package io.metaloom.loom.agent.sandbox.error;

/**
 * A retryable sandbox failure — provisioning could not complete right now but may succeed later
 * (e.g. the per-deployment concurrency cap was hit, or the scheduler could not place the pod yet).
 * The UI can surface these as a "try again" state.
 */
public class SandboxRetryableException extends SandboxException {

	private static final long serialVersionUID = 1L;

	public SandboxRetryableException(String message) {
		super(message);
	}

	public SandboxRetryableException(String message, Throwable cause) {
		super(message, cause);
	}
}
