package io.metaloom.cortex.node.script.engine;

/**
 * A script that has been parsed and is ready to run against a set of bindings.
 *
 * <p>
 * Closing releases the engine's per-script resources (for GraalJS, the polyglot context). A node
 * closes its compiled script when it is reconfigured with a different source.
 * </p>
 */
public interface CompiledScript extends AutoCloseable {

	/**
	 * Run the script once.
	 *
	 * <p>
	 * Everything the script may read or produce arrives through {@code bindings}; the script has
	 * no other channel. Normal completion means the script finished without calling
	 * {@code ctx.fail(...)}; whether it produced anything is the caller's business to check
	 * against the declared outputs.
	 * </p>
	 *
	 * @param bindings the values exposed to the script and the collector it writes through
	 * @throws ScriptException on a guest error, or when a limit cancelled execution
	 *                         ({@link ScriptException#isCancelled()})
	 */
	void execute(ScriptBindings bindings) throws ScriptException;

	@Override
	void close();
}
