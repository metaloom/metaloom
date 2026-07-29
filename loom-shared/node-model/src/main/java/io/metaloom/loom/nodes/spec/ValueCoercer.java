package io.metaloom.loom.nodes.spec;

import java.util.Collection;
import java.util.Map;

/**
 * Coerces a value to the Java type its port's content type implies, and rejects what cannot be coerced.
 *
 * <p>
 * Applied at <strong>both</strong> wire boundaries — when a node emits a value, and again when the engine or a downstream node reads one. Running it
 * twice is not redundant: the JSON round trip in between re-narrows a {@code Long} that happens to fit in 32 bits back to an {@code Integer}, so the
 * read-side pass is what re-widens it. That single fact is why {@code scalar/integer} merged the former {@code data/integer} and {@code data/long},
 * and why the long-standing {@code ClassCastException} on {@code video_frame_count} stops being reachable.
 * </p>
 *
 * <p>
 * Generalises {@code ScriptOutputCollector.coerce}, which was previously the only place in the system where a declared type was actually enforced.
 * </p>
 */
public final class ValueCoercer {

	private ValueCoercer() {
	}

	/**
	 * Coerce a single element value against a port's declared content type.
	 *
	 * @param portId
	 *            the port, used only for the error message
	 * @param contentType
	 *            the declared content type
	 * @param value
	 *            the value to coerce
	 * @return the coerced value, never null
	 * @throws ValueCoercionException
	 *             when the value cannot satisfy the declared type
	 */
	public static Object coerce(String portId, String contentType, Object value) {
		if (value == null) {
			throw new ValueCoercionException(portId, contentType, "value is null");
		}
		String family = ContentTypeLattice.family(contentType);
		if (family == null) {
			throw new ValueCoercionException(portId, contentType, "content type is not 'family/subtype'");
		}
		return switch (family) {
			case "scalar" -> coerceScalar(portId, contentType, value);
			case "control" -> coerceBoolean(portId, contentType, value);
			case "struct" -> coerceStruct(portId, contentType, value);
			// media, text, detection, hash and artifact all travel as strings: a reference, prose,
			// a JSON document or a worker-local path.
			case "media", "text", "hash", "artifact" -> coerceString(portId, contentType, value);
			case "detection" -> coerceDetection(portId, contentType, value);
			default -> throw new ValueCoercionException(portId, contentType, "unknown content-type family '" + family + "'");
		};
	}

	private static Object coerceScalar(String portId, String contentType, Object value) {
		return switch (contentType) {
			case ContentTypeRegistry.SCALAR_INTEGER -> coerceInteger(portId, contentType, value);
			case ContentTypeRegistry.SCALAR_NUMBER -> coerceNumber(portId, contentType, value);
			case ContentTypeRegistry.SCALAR_BOOLEAN -> coerceBoolean(portId, contentType, value);
			case ContentTypeRegistry.SCALAR_STRING -> coerceString(portId, contentType, value);
			// scalar/* accepts any primitive as-is
			case ContentTypeRegistry.SCALAR_ANY -> {
				if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
					yield value instanceof CharSequence cs ? cs.toString() : value;
				}
				throw new ValueCoercionException(portId, contentType, "expected a primitive, got " + typeOf(value));
			}
			default -> throw new ValueCoercionException(portId, contentType, "unknown scalar type");
		};
	}

	/**
	 * Always widens to {@code Long}. This is the single reason the JSON narrowing stops being observable.
	 */
	private static Long coerceInteger(String portId, String contentType, Object value) {
		if (value instanceof Number n) {
			if (n instanceof Double || n instanceof Float) {
				double d = n.doubleValue();
				if (d != Math.rint(d)) {
					throw new ValueCoercionException(portId, contentType, "expected a whole number, got " + d);
				}
			}
			return n.longValue();
		}
		if (value instanceof CharSequence cs) {
			try {
				return Long.valueOf(cs.toString().trim());
			} catch (NumberFormatException e) {
				throw new ValueCoercionException(portId, contentType, "cannot read '" + cs + "' as a whole number");
			}
		}
		throw new ValueCoercionException(portId, contentType, "expected a whole number, got " + typeOf(value));
	}

	private static Double coerceNumber(String portId, String contentType, Object value) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		if (value instanceof CharSequence cs) {
			try {
				return Double.valueOf(cs.toString().trim());
			} catch (NumberFormatException e) {
				throw new ValueCoercionException(portId, contentType, "cannot read '" + cs + "' as a number");
			}
		}
		throw new ValueCoercionException(portId, contentType, "expected a number, got " + typeOf(value));
	}

	/**
	 * Tolerates a stringified boolean because the Cortex disk caches stringify every value they hold.
	 */
	private static Boolean coerceBoolean(String portId, String contentType, Object value) {
		if (value instanceof Boolean b) {
			return b;
		}
		if (value instanceof CharSequence cs) {
			String s = cs.toString().trim();
			if ("true".equalsIgnoreCase(s)) {
				return Boolean.TRUE;
			}
			if ("false".equalsIgnoreCase(s)) {
				return Boolean.FALSE;
			}
		}
		throw new ValueCoercionException(portId, contentType, "expected a boolean, got " + typeOf(value));
	}

	private static String coerceString(String portId, String contentType, Object value) {
		if (value instanceof CharSequence cs) {
			return cs.toString();
		}
		throw new ValueCoercionException(portId, contentType, "expected a string, got " + typeOf(value));
	}

	/**
	 * A detection element is a structured box. It may arrive as a map or as an already-encoded JSON string.
	 */
	private static Object coerceDetection(String portId, String contentType, Object value) {
		if (value instanceof Map<?, ?> || value instanceof CharSequence) {
			return value instanceof CharSequence cs ? cs.toString() : value;
		}
		throw new ValueCoercionException(portId, contentType, "expected a detection object or JSON string, got " + typeOf(value));
	}

	/**
	 * Structured payloads must be JSON-encodable <em>here</em>, at the boundary. Previously a non-encodable value was wrapped without validation and
	 * only blew up at persist time, where the catch cleared both the item and the task buffer — losing a whole batch to one bad value.
	 */
	private static Object coerceStruct(String portId, String contentType, Object value) {
		if (value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof CharSequence) {
			return value;
		}
		throw new ValueCoercionException(portId, contentType, "expected a JSON object, array or encoded string, got " + typeOf(value));
	}

	private static String typeOf(Object value) {
		return value.getClass().getSimpleName();
	}
}
