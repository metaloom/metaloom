package io.metaloom.cortex.node.script.engine.js;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import io.metaloom.cortex.node.script.ScriptValueType;
import io.metaloom.cortex.node.script.engine.CompiledScript;
import io.metaloom.cortex.node.script.engine.ScriptBindings;
import io.metaloom.cortex.node.script.engine.ScriptException;
import io.metaloom.cortex.node.script.engine.ScriptLimits;
import io.metaloom.cortex.node.script.engine.ScriptOutputException;
import io.metaloom.cortex.node.script.engine.ScriptSignal;
import io.vertx.core.json.JsonObject;

/**
 * A parsed JavaScript script, executed inside a fresh polyglot context per run.
 *
 * <h3>Why a context per execution</h3>
 * <p>
 * Reusing one context across media items would be faster, but a script could then stash state in
 * a global and leak it from one asset to the next - the kind of bug that only shows up in
 * production, under concurrency, on the tenth file. A fresh context makes each execution
 * independent by construction. The {@link Source} is parsed once by the engine, so the repeated
 * cost is context setup, not parsing.
 * </p>
 *
 * <h3>Two independent stop conditions</h3>
 * <p>
 * The statement limit catches a tight loop deterministically; the wall-clock watchdog catches
 * everything else, including a script blocked in a host call the statement counter never
 * advances through. Neither alone is sufficient.
 * </p>
 */
public class GraalJsCompiledScript implements CompiledScript {

	/**
	 * Shared watchdog. One daemon thread is enough: it only sleeps and then cancels a context, so
	 * it is never the bottleneck, and a per-script thread would be one more thing to leak.
	 */
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "script-node-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	private final Source source;
	private final ScriptLimits limits;

	public GraalJsCompiledScript(Source source, ScriptLimits limits) {
		this.source = source;
		this.limits = limits;
	}

	@Override
	public void execute(ScriptBindings bindings) throws ScriptException {
		AtomicBoolean timedOut = new AtomicBoolean();
		Context context = newContext();
		ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
			timedOut.set(true);
			// close(true) interrupts the running guest thread; a plain close would block on it.
			try {
				context.close(true);
			} catch (Exception ignored) {
				// Racing a normal close is expected and harmless.
			}
		}, limits.timeoutMs(), TimeUnit.MILLISECONDS);

		try {
			install(context, bindings);
			context.eval(source);
		} catch (PolyglotException e) {
			throw translate(e, timedOut.get());
		} catch (ScriptOutputException | ScriptSignal e) {
			// Thrown by a host binding and propagated straight out when it is not wrapped.
			throw e;
		} catch (Exception e) {
			throw new ScriptException("script failed: " + e.getMessage(), e);
		} finally {
			watchdog.cancel(false);
			try {
				context.close(true);
			} catch (Exception ignored) {
				// Already closed by the watchdog.
			}
		}
	}

	/**
	 * Convert a guest exception into either a signal the node understands or a script failure.
	 *
	 * <p>
	 * A host exception thrown by one of our bindings arrives wrapped in a {@link PolyglotException};
	 * unwrapping it is what makes {@code ctx.skip(...)} inside the script produce a skipped node
	 * rather than a failure with a JavaScript stack attached.
	 * </p>
	 */
	private ScriptException translate(PolyglotException e, boolean timedOut) {
		if (e.isHostException()) {
			Throwable host = e.asHostException();
			if (host instanceof ScriptSignal signal) {
				throw signal;
			}
			if (host instanceof ScriptOutputException output) {
				throw output;
			}
		}
		if (timedOut) {
			return new ScriptException("script exceeded its " + limits.timeoutMs() + "ms time budget", e, true);
		}
		if (e.isCancelled() || e.isResourceExhausted()) {
			return new ScriptException("script exceeded its statement budget of " + limits.statementLimit(), e, true);
		}
		// The guest message is the useful part; a host stack would only point back at this class.
		return new ScriptException(firstLine(e.getMessage()), e);
	}

	private static String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "script failed";
		}
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}

	private Context newContext() {
		Context.Builder builder = Context.newBuilder(GraalJsScriptEngine.ID)
			// Deterministic guard against a tight loop, and portable: unlike CPU-time limits this
			// works on the fallback interpreter, which is what a stock JDK gives us.
			.resourceLimits(ResourceLimits.newBuilder().statementLimit(limits.statementLimit(), null).build())
			// Every script node compiles a script; logging the "no runtime compilation" notice each
			// time would drown the worker log in a warning the operator can do nothing about.
			.option("engine.WarnInterpreterOnly", "false");

		if (limits.trusted()) {
			builder.allowAllAccess(true);
		} else {
			// A capability model, not a policy: the script reaches exactly the host objects we
			// hand it and nothing else. HostAccess.EXPLICIT additionally means only @HostAccess
			// -annotated members are reachable - and our bindings expose none - so the Proxy
			// objects installed below are the entire surface.
			builder.allowHostAccess(HostAccess.EXPLICIT)
				.allowHostClassLookup(className -> false)
				.allowCreateThread(false)
				.allowCreateProcess(false)
				.allowNativeAccess(false)
				.allowIO(null)
				.allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE);
		}
		return builder.build();
	}

	/** Install the bindings. This set is the node's documented public API - change it additively. */
	private void install(Context context, ScriptBindings bindings) {
		Value scope = context.getBindings(GraalJsScriptEngine.ID);
		scope.putMember("media", toGuest(bindings.media()));
		scope.putMember("upstream", toGuest(bindings.upstream()));
		scope.putMember("params", toGuest(bindings.params()));
		scope.putMember("out", outBinding(bindings));
		scope.putMember("log", logBinding(bindings));
		scope.putMember("ctx", ctxBinding());
	}

	private static ProxyObject outBinding(ScriptBindings bindings) {
		Map<String, Object> members = new LinkedHashMap<>();
		members.put("set", (ProxyExecutable) args -> emit(bindings, args));
		members.put("text", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.TEXT, ScriptValueType.STRING));
		members.put("string", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.STRING, ScriptValueType.TEXT));
		members.put("integer", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.INTEGER));
		members.put("number", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.NUMBER, ScriptValueType.INTEGER));
		members.put("bool", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.BOOLEAN));
		members.put("json", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.JSON));
		members.put("list", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.TEXT_LIST));
		members.put("timeframes", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.TIMEFRAMES));
		members.put("image", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.IMAGE, ScriptValueType.IMAGE_LIST));
		members.put("path", (ProxyExecutable) args -> emit(bindings, args, ScriptValueType.PATH));
		return ProxyObject.fromMap(members);
	}

	private static Object emit(ScriptBindings bindings, Value[] args, ScriptValueType... expected) {
		if (args.length < 2) {
			throw new ScriptOutputException("out.* requires a key and a value");
		}
		String key = args[0].asString();
		Object value = fromGuest(args[1]);
		if (expected.length == 0) {
			bindings.out().set(key, value);
		} else {
			bindings.out().setTyped(key, value, expected);
		}
		return null;
	}

	private static ProxyObject logBinding(ScriptBindings bindings) {
		Map<String, Object> members = new LinkedHashMap<>();
		members.put("info", (ProxyExecutable) args -> logLine(bindings, args, "info"));
		members.put("warn", (ProxyExecutable) args -> logLine(bindings, args, "warn"));
		members.put("error", (ProxyExecutable) args -> logLine(bindings, args, "error"));
		return ProxyObject.fromMap(members);
	}

	private static Object logLine(ScriptBindings bindings, Value[] args, String level) {
		Object message = args.length == 0 ? "" : fromGuest(args[0]);
		switch (level) {
			case "warn" -> bindings.log().warn(message);
			case "error" -> bindings.log().error(message);
			default -> bindings.log().info(message);
		}
		return null;
	}

	private static ProxyObject ctxBinding() {
		Map<String, Object> members = new LinkedHashMap<>();
		members.put("skip", (ProxyExecutable) args -> {
			throw ScriptSignal.skip(args.length == 0 ? null : args[0].asString());
		});
		members.put("fail", (ProxyExecutable) args -> {
			throw ScriptSignal.fail(args.length == 0 ? null : args[0].asString());
		});
		return ProxyObject.fromMap(members);
	}

	/**
	 * Convert a host value into something the guest can read.
	 *
	 * <p>
	 * Maps and lists are wrapped in proxies rather than handed over directly so that a sandboxed
	 * script cannot reach host methods on them - a raw {@code java.util.Map} would expose its
	 * whole API once host access were ever widened.
	 * </p>
	 */
	private static Object toGuest(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> converted = new LinkedHashMap<>();
			map.forEach((key, element) -> converted.put(String.valueOf(key), toGuest(element)));
			return ProxyObject.fromMap(converted);
		}
		if (value instanceof JsonObject json) {
			return toGuest(json.getMap());
		}
		if (value instanceof List<?> list) {
			List<Object> converted = new ArrayList<>(list.size());
			for (Object element : list) {
				converted.add(toGuest(element));
			}
			return ProxyArray.fromList(converted);
		}
		return value;
	}

	/** Convert a guest value into plain Java for the collector to coerce. */
	private static Object fromGuest(Value value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isBoolean()) {
			return value.asBoolean();
		}
		if (value.isString()) {
			return value.asString();
		}
		if (value.isNumber()) {
			// Keep whole numbers whole: a JS number is a double, and turning 3 into 3.0 here would
			// surface as "3.0" in a text output.
			return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
		}
		if (value.hasArrayElements()) {
			List<Object> list = new ArrayList<>((int) value.getArraySize());
			for (long i = 0; i < value.getArraySize(); i++) {
				list.add(fromGuest(value.getArrayElement(i)));
			}
			return list;
		}
		if (value.hasMembers()) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (String key : value.getMemberKeys()) {
				map.put(key, fromGuest(value.getMember(key)));
			}
			return map;
		}
		return value.toString();
	}

	@Override
	public void close() {
		// The Source is immutable and every Context is closed at the end of its execution, so
		// there is nothing left to release here.
	}
}
