package io.metaloom.cortex.node.script.engine;

/**
 * A language backend for the {@code script} node.
 *
 * <p>
 * Engines are contributed to Dagger the same way node kinds are - a one-line
 * {@code @Binds @IntoMap @StringKey("<id>")} in the engine's own module - so adding a language is
 * additive and never touches {@code ScriptNode}.
 * </p>
 *
 * <p>
 * Implementations must be safe to share across threads: one engine instance serves every script
 * node on the worker. Per-execution state belongs in {@link CompiledScript}, and even there the
 * node serialises execution per compiled script (see {@code ScriptNode}).
 * </p>
 */
public interface ScriptEngine {

	/**
	 * Stable identifier used by the {@code engine} node option - {@code "js"}, {@code "groovy"}.
	 */
	String id();

	/**
	 * Human-readable language name, used in error messages and in the produced-version string.
	 */
	String language();

	/**
	 * Compile a script once, for repeated execution across media items.
	 *
	 * <p>
	 * Compilation is deliberately separate from execution: a script node runs the same source for
	 * every item in a run, and compiling per item turns a millisecond of work into hundreds.
	 * </p>
	 *
	 * @param source  the script body
	 * @param limits  the envelope the compiled script must run inside
	 * @return the compiled script, owned by the caller and closed by it
	 * @throws ScriptException when the source does not compile
	 */
	CompiledScript compile(String source, ScriptLimits limits) throws ScriptException;
}
