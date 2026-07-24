package io.metaloom.loom.agent.sandbox.error;

/**
 * The per-deployment concurrency cap (or the backend namespace quota) has been reached. Retryable —
 * the caller should try again once another session's runner has been reaped.
 */
public class SandboxQuotaException extends SandboxRetryableException {

	private static final long serialVersionUID = 1L;

	public SandboxQuotaException(String message) {
		super(message);
	}
}
