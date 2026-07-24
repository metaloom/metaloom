package io.metaloom.loom.agent.sandbox.error;

/**
 * The Session Runner was created but never became healthy within the ready timeout. Non-retryable in
 * the immediate sense — the runner is torn down before this is raised.
 */
public class SandboxUnhealthyException extends SandboxException {

	private static final long serialVersionUID = 1L;

	public SandboxUnhealthyException(String message) {
		super(message);
	}
}
