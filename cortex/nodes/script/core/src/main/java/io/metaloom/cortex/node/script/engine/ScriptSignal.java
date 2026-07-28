package io.metaloom.cortex.node.script.engine;

/**
 * Thrown by {@code ctx.skip(...)} / {@code ctx.fail(...)} to end a script early with an explicit
 * outcome.
 *
 * <p>
 * Control flow rather than an error: a script that decides there is nothing to do for this media
 * item should produce {@code SKIPPED}, not a failure that reddens the run. Modelling both as a
 * throw is what lets a script bail out of a nested helper without threading a return value back
 * to the top level.
 * </p>
 */
public class ScriptSignal extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final boolean failure;

	private ScriptSignal(String reason, boolean failure) {
		// No stack trace: this is a signal, and the guest stack is meaningless to the author.
		super(reason, null, false, false);
		this.failure = failure;
	}

	public static ScriptSignal skip(String reason) {
		return new ScriptSignal(reason == null || reason.isBlank() ? "skipped by script" : reason, false);
	}

	public static ScriptSignal fail(String reason) {
		return new ScriptSignal(reason == null || reason.isBlank() ? "failed by script" : reason, true);
	}

	/** True for {@code ctx.fail(...)}, false for {@code ctx.skip(...)}. */
	public boolean isFailure() {
		return failure;
	}
}
