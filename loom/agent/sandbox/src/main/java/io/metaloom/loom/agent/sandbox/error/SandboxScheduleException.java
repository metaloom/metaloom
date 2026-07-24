package io.metaloom.loom.agent.sandbox.error;

/**
 * The backend could not schedule/start the Session Runner (podman command failed, or the pod is
 * stuck Unschedulable). Retryable.
 */
public class SandboxScheduleException extends SandboxRetryableException {

	private static final long serialVersionUID = 1L;

	public SandboxScheduleException(String message) {
		super(message);
	}

	public SandboxScheduleException(String message, Throwable cause) {
		super(message, cause);
	}
}
