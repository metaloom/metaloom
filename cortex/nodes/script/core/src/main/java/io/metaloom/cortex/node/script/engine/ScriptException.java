package io.metaloom.cortex.node.script.engine;

/**
 * Raised when a script fails to compile or fails during execution.
 *
 * <p>
 * The message is shown to a pipeline author, so implementations should carry the guest-language
 * error rather than a wrapper's own words.
 * </p>
 */
public class ScriptException extends Exception {

	private static final long serialVersionUID = 1L;

	/** Whether the script was stopped by a limit (timeout or statement budget) rather than by an error in the script itself. */
	private final boolean cancelled;

	public ScriptException(String message) {
		this(message, null, false);
	}

	public ScriptException(String message, Throwable cause) {
		this(message, cause, false);
	}

	public ScriptException(String message, Throwable cause, boolean cancelled) {
		super(message, cause);
		this.cancelled = cancelled;
	}

	/**
	 * True when execution was cut short by {@code timeoutMs} or the statement limit. Callers use
	 * this to report "the script ran too long" rather than a confusing guest-language stack.
	 */
	public boolean isCancelled() {
		return cancelled;
	}
}
