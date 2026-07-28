package io.metaloom.cortex.node.script.engine.js;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import io.metaloom.cortex.node.script.engine.CompiledScript;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.ScriptException;
import io.metaloom.cortex.node.script.engine.ScriptLimits;

/**
 * JavaScript engine backed by GraalJS.
 *
 * <p>
 * Chosen as the first engine for one concrete reason: it is the only candidate that can be
 * meaningfully restricted in-process on a modern JDK. {@code SecurityManager} was removed in JDK
 * 24, so every JVM-native scripting language (Groovy included) can only be constrained by an AST
 * allow-list, which is best-effort. GraalJS's {@code Context.Builder} is an actual capability
 * model: the script sees only the host objects it is explicitly handed.
 * </p>
 *
 * <p>
 * <strong>Performance.</strong> On a stock JDK there is no Graal JIT, so scripts run on Truffle's
 * fallback interpreter. That is fine for the glue logic this node exists for and wrong for tight
 * numeric loops - a caller wanting the latter should write a Java node. The interpreter warning
 * is suppressed because it would otherwise be logged for every compile.
 * </p>
 */
@Singleton
public class GraalJsScriptEngine implements ScriptEngine {

	public static final String ID = "js";

	@Inject
	public GraalJsScriptEngine() {
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String language() {
		return "javascript";
	}

	@Override
	public CompiledScript compile(String source, ScriptLimits limits) throws ScriptException {
		if (source == null || source.isBlank()) {
			throw new ScriptException("script must not be empty");
		}
		Source parsed;
		try {
			parsed = Source.newBuilder(ID, source, "pipeline-script.js").build();
		} catch (Exception e) {
			throw new ScriptException("failed to read script: " + e.getMessage(), e);
		}

		// Building a Source does not parse it - GraalJS parses lazily on first eval. Parse here in a
		// throwaway context so a syntax error surfaces once, when the node is configured, instead of
		// identically for every media item in the run.
		try (Context probe = Context.newBuilder(ID).option("engine.WarnInterpreterOnly", "false").build()) {
			probe.parse(parsed);
		} catch (PolyglotException e) {
			throw new ScriptException(firstLine(e.getMessage()), e);
		} catch (Exception e) {
			throw new ScriptException("failed to parse script: " + e.getMessage(), e);
		}

		return new GraalJsCompiledScript(parsed, limits);
	}

	/** The guest parser's first line is the useful part; the rest is a location dump. */
	private static String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "script did not parse";
		}
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
