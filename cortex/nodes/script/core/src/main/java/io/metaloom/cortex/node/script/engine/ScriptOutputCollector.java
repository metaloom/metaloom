package io.metaloom.cortex.node.script.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.node.script.ScriptOutputSpec;
import io.metaloom.cortex.node.script.ScriptValueType;
import io.vertx.core.json.JsonObject;

/**
 * The {@code out} binding: the only channel through which a script produces results.
 *
 * <p>
 * Values are checked against the node's <em>declared</em> outputs as they arrive rather than at
 * the end, so the error names the offending call while the author still has it in front of them.
 * Writing to an undeclared key is a hard failure, not a warning: a silently dropped typo would
 * leave the graph claiming an edge carries data that never flows.
 * </p>
 */
public class ScriptOutputCollector {

	/**
	 * Destination for image bytes. Images do not go to Loom - there is no byte-ingest endpoint
	 * for produced media - so the node supplies a sink that writes them to its local cache and
	 * returns the path that becomes the emitted value.
	 */
	@FunctionalInterface
	public interface BinarySink {
		String write(String outputKey, int index, byte[] bytes) throws IOException;
	}

	private final Map<String, ScriptOutputSpec> declared = new LinkedHashMap<>();
	private final Map<String, Object> values = new LinkedHashMap<>();
	private final BinarySink binarySink;

	public ScriptOutputCollector(List<ScriptOutputSpec> specs, BinarySink binarySink) {
		for (ScriptOutputSpec spec : specs) {
			declared.put(spec.key(), spec);
		}
		this.binarySink = binarySink;
	}

	/**
	 * Emit a value, coercing it to the declared type of {@code key}.
	 *
	 * @throws ScriptOutputException when the key is not declared or the value cannot be coerced
	 */
	public void set(String key, Object value) {
		ScriptOutputSpec spec = declared.get(key);
		if (spec == null) {
			throw new ScriptOutputException("output '" + key + "' is not declared by this node; declared outputs are " + declared.keySet());
		}
		if (value == null) {
			throw new ScriptOutputException("output '" + key + "' was set to null; omit the call instead to leave it unset");
		}
		values.put(key, coerce(spec, value));
	}

	/**
	 * Emit a value and additionally require that the declared type is one of {@code expected}.
	 * This is what makes {@code out.timeframes(k, v)} on a {@code TEXT} output an error naming
	 * both types, rather than a confusing coercion failure.
	 */
	public void setTyped(String key, Object value, ScriptValueType... expected) {
		ScriptOutputSpec spec = declared.get(key);
		if (spec != null) {
			boolean ok = false;
			for (ScriptValueType type : expected) {
				ok |= spec.type() == type;
			}
			if (!ok) {
				throw new ScriptOutputException("output '" + key + "' is declared as " + spec.type()
					+ " but was written with a call for " + java.util.Arrays.toString(expected));
			}
		}
		set(key, value);
	}

	/** The collected values, keyed by output key, ready for {@code ctx.output(...)}. */
	public Map<String, Object> values() {
		return values;
	}

	/** The declared spec for a key that was set, or null. */
	public ScriptOutputSpec specOf(String key) {
		return declared.get(key);
	}

	/** All declared specs, in declaration order. */
	public List<ScriptOutputSpec> declared() {
		return new ArrayList<>(declared.values());
	}

	private Object coerce(ScriptOutputSpec spec, Object value) {
		String key = spec.key();
		return switch (spec.type()) {
			case STRING, TEXT, PATH -> String.valueOf(value);
			case INTEGER -> asNumber(key, value).longValue();
			case NUMBER -> asNumber(key, value).doubleValue();
			case BOOLEAN -> asBoolean(key, value);
			case JSON -> asJson(key, value);
			case TEXT_LIST -> asTextList(key, value);
			case TIMEFRAMES -> asTimeframes(key, value);
			case IMAGE -> writeImages(key, List.of(value)).get(0);
			case IMAGE_LIST -> writeImages(key, asList(key, value));
		};
	}

	private static Number asNumber(String key, Object value) {
		if (value instanceof Number number) {
			return number;
		}
		try {
			return Double.valueOf(String.valueOf(value));
		} catch (NumberFormatException e) {
			throw new ScriptOutputException("output '" + key + "' expects a number but got '" + value + "'");
		}
	}

	private static Boolean asBoolean(String key, Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		String text = String.valueOf(value);
		if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
			return Boolean.parseBoolean(text);
		}
		throw new ScriptOutputException("output '" + key + "' expects a boolean but got '" + value + "'");
	}

	private static JsonObject asJson(String key, Object value) {
		if (value instanceof JsonObject json) {
			return json;
		}
		if (value instanceof Map<?, ?> map) {
			return new JsonObject(new LinkedHashMap<>(castMap(map)));
		}
		if (value instanceof String text) {
			try {
				return new JsonObject(text);
			} catch (RuntimeException e) {
				throw new ScriptOutputException("output '" + key + "' expects a JSON object but the string did not parse: " + e.getMessage());
			}
		}
		throw new ScriptOutputException("output '" + key + "' expects a JSON object but got " + value.getClass().getSimpleName());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private static List<String> asTextList(String key, Object value) {
		List<String> out = new ArrayList<>();
		for (Object element : asList(key, value)) {
			out.add(element == null ? "" : String.valueOf(element));
		}
		return out;
	}

	private static List<?> asList(String key, Object value) {
		if (value instanceof List<?> list) {
			return list;
		}
		throw new ScriptOutputException("output '" + key + "' expects a list but got " + value.getClass().getSimpleName());
	}

	/**
	 * Coerce a timeframe list. Each entry needs a start and an end; {@code label} and {@code data}
	 * are optional. Ranges are validated here rather than at persistence time so the error points
	 * at the script instead of at a REST rejection.
	 */
	private static List<JsonObject> asTimeframes(String key, Object value) {
		List<JsonObject> frames = new ArrayList<>();
		List<?> list = asList(key, value);
		for (int i = 0; i < list.size(); i++) {
			Object raw = list.get(i);
			Map<String, Object> map;
			if (raw instanceof JsonObject json) {
				map = json.getMap();
			} else if (raw instanceof Map<?, ?> m) {
				map = castMap(m);
			} else {
				throw new ScriptOutputException("output '" + key + "'[" + i + "] must be an object with startMs and endMs");
			}
			Object start = map.get("startMs");
			Object end = map.get("endMs");
			if (start == null || end == null) {
				throw new ScriptOutputException("output '" + key + "'[" + i + "] needs both startMs and endMs");
			}
			long startMs = asNumber(key, start).longValue();
			long endMs = asNumber(key, end).longValue();
			if (startMs < 0 || endMs < startMs) {
				throw new ScriptOutputException("output '" + key + "'[" + i + "] has an invalid range " + startMs + ".." + endMs);
			}
			JsonObject frame = new JsonObject().put("startMs", startMs).put("endMs", endMs);
			Object label = map.get("label");
			if (label != null) {
				frame.put("label", String.valueOf(label));
			}
			Object data = map.get("data");
			if (data instanceof Map<?, ?> dataMap) {
				frame.put("data", new JsonObject(new LinkedHashMap<>(castMap(dataMap))));
			} else if (data instanceof JsonObject dataJson) {
				frame.put("data", dataJson);
			}
			frames.add(frame);
		}
		return frames;
	}

	/**
	 * Write image values through the sink and return their paths.
	 *
	 * <p>
	 * An image may arrive as raw bytes, as a base64 string, or as a path to a file the script
	 * already produced. The path form is passed through untouched - re-reading and re-writing it
	 * would only copy bytes around.
	 * </p>
	 */
	private List<String> writeImages(String key, List<?> images) {
		if (binarySink == null) {
			throw new ScriptOutputException("output '" + key + "' produces images but this node has no binary sink configured");
		}
		List<String> paths = new ArrayList<>();
		for (int i = 0; i < images.size(); i++) {
			Object raw = images.get(i);
			if (raw instanceof String text && !looksBase64(text)) {
				paths.add(text);
				continue;
			}
			byte[] bytes = asBytes(key, i, raw);
			try {
				paths.add(binarySink.write(key, i, bytes));
			} catch (IOException e) {
				throw new ScriptOutputException("output '" + key + "'[" + i + "] could not be written: " + e.getMessage(), e);
			}
		}
		return paths;
	}

	/** A path never contains base64 padding or runs to hundreds of characters without a separator. */
	private static boolean looksBase64(String text) {
		return text.length() > 256 && text.indexOf('/') < 0 || text.endsWith("==");
	}

	private static byte[] asBytes(String key, int index, Object raw) {
		if (raw instanceof byte[] bytes) {
			return bytes;
		}
		if (raw instanceof String text) {
			try {
				return Base64.getDecoder().decode(text);
			} catch (IllegalArgumentException e) {
				throw new ScriptOutputException("output '" + key + "'[" + index + "] is a string but is neither a path nor valid base64");
			}
		}
		if (raw instanceof List<?> list) {
			byte[] bytes = new byte[list.size()];
			for (int i = 0; i < list.size(); i++) {
				bytes[i] = (byte) asNumber(key, list.get(i)).intValue();
			}
			return bytes;
		}
		throw new ScriptOutputException("output '" + key + "'[" + index + "] must be bytes, a base64 string, or a path");
	}
}
