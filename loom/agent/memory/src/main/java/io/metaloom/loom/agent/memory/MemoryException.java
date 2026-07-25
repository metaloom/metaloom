package io.metaloom.loom.agent.memory;

/**
 * Raised when a memory operation is rejected — an invalid id, an unavailable scope, or a quota violation.
 *
 * <p>The message is written to be read by the model: it states what was wrong and what to do instead, because in the agentic loop this becomes an
 * <em>error tool result</em> rather than a failure (the loop continues so the model can react).</p>
 */
public class MemoryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public MemoryException(String message) {
		super(message);
	}

	public MemoryException(String message, Throwable cause) {
		super(message, cause);
	}

}
