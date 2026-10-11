package io.metaloom.cortex.common.node.reuse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.ContentTypeLattice;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The mechanics of node output reuse: the key a node is looked up by, and the stored form of what it emitted.
 *
 * <p>
 * A node may skip its work when Loom already holds what it emitted for the same asset given the same question. "The same question" is the
 * {@link #reuseKey reuse key}: a SHA-256 over everything that can change the answer - the node kind, its reuse version, the shape of its output ports,
 * its options and the values arriving on its non-media input ports. The asset itself is not in the key; the store is per asset already.
 * </p>
 *
 * <p>
 * The stored form is the node's port values exactly as they cross the wire, which is what makes reuse generic: replaying them feeds the rest of the
 * graph without any node-specific code that rebuilds a port value from a component table.
 * </p>
 *
 * <p>
 * Two worker-local details are normalised so a value stays valid on another worker: the item's own media path is stored as {@link #MEDIA_PLACEHOLDER}
 * and put back on replay, and any other file a value points at must still exist on this worker, or the stored output is not used.
 * </p>
 */
public final class NodeOutputReuse {

	/** Stands for the path of the item's own media in a stored value; replaced with the current path on replay. */
	public static final String MEDIA_PLACEHOLDER = "$media";

	/** A stored output larger than this is not worth a database row; the node simply computes next time. */
	public static final int MAX_STORED_BYTES = 4 * 1024 * 1024;

	/**
	 * Option keys that never change what a node emits: they decide whether the node runs at all, how long it may take, or whether it reuses - and
	 * {@code recompute} must not be in the key, or recomputing would store under a key no plain run ever looks up.
	 */
	static final Set<String> IGNORED_OPTION_KEYS = Set.of("enabled", "recompute", "timeoutMs", "retryFailed");

	/** Structural keys of a pipeline node definition, as flattened by {@code NodeTaskRunner}, plus the reuse switch itself. */
	static final Set<String> IGNORED_DEFINITION_KEYS = Set.of("id", "type", "recompute", "enabled", "mode", "blocking", "concurrency", "timeoutMs",
		"syncToLoom", "retryFailed");

	private static final Map<Class<?>, String> OUTPUT_SIGNATURES = new ConcurrentHashMap<>();

	private NodeOutputReuse() {
	}

	/**
	 * Compute the reuse key.
	 *
	 * @param kind            the node kind
	 * @param version         the node's reuse version - bumped by hand when the node's output changes for the same input
	 * @param outputSignature the node's declared output ports, from {@link #outputSignature(Class)}
	 * @param options         the node's options object; serialised with Jackson
	 * @param definition      the pipeline node definition, for a node configured from it; null otherwise
	 * @param nodeId          the graph-local node id, for a node whose stored results are scoped to its instance; null otherwise
	 * @param inputs          the values on the node's input ports
	 * @param mediaPath       the item's own media path, normalised out of input values
	 * @return a lowercase hex SHA-256
	 */
	public static String reuseKey(String kind, String version, String outputSignature, Object options, JsonObject definition, String nodeId,
		Map<String, PortPayload> inputs, String mediaPath) {
		Map<String, Object> material = new TreeMap<>();
		material.put("kind", kind);
		material.put("version", version);
		material.put("outputs", outputSignature);
		material.put("options", canonical(without(options == null ? new JsonObject() : JsonObject.mapFrom(options), IGNORED_OPTION_KEYS)));
		if (definition != null) {
			material.put("definition", canonical(without(definition, IGNORED_DEFINITION_KEYS)));
		}
		if (nodeId != null) {
			material.put("nodeId", nodeId);
		}
		Map<String, Object> ports = new TreeMap<>();
		if (inputs != null) {
			inputs.forEach((portId, payload) -> {
				if (payload == null) {
					return;
				}
				Map<String, Object> port = new TreeMap<>();
				port.put("contentType", payload.getContentType());
				port.put("values", canonical(normaliseMedia(payload.values(), mediaPath)));
				ports.put(portId, port);
			});
		}
		material.put("inputs", ports);
		return sha256(Json.encode(material));
	}

	/**
	 * The declared output ports of a node class, as one stable string. Part of the key so that changing a port's type or cardinality invalidates what
	 * was stored under the old shape without anybody remembering to bump a version.
	 */
	public static String outputSignature(Class<?> nodeClass) {
		return OUTPUT_SIGNATURES.computeIfAbsent(nodeClass, NodeOutputReuse::reflectOutputSignature);
	}

	private static String reflectOutputSignature(Class<?> nodeClass) {
		Set<String> ports = new TreeSet<>();
		for (Class<?> c = nodeClass; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) || !OutputPort.class.isAssignableFrom(field.getType())) {
					continue;
				}
				try {
					field.setAccessible(true);
					if (field.get(null) instanceof OutputPort<?> port) {
						ports.add(port.id() + "|" + port.contentType() + "|" + port.cardinality());
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					// An unreadable constant cannot be part of the signature; the rest still is.
				}
			}
		}
		return String.join(";", ports);
	}

	/**
	 * Turn what a node emitted into the stored form: port id to {@code {contentType, cardinality, values}}.
	 *
	 * @param outputs   the node's port outputs
	 * @param mediaPath the item's own media path, stored as {@link #MEDIA_PLACEHOLDER}
	 * @return the stored form, or null when it is too large to be worth storing or a value cannot be encoded
	 */
	public static JsonObject encode(Map<String, PortOutput> outputs, String mediaPath) {
		JsonObject stored = new JsonObject();
		for (Map.Entry<String, PortOutput> entry : outputs.entrySet()) {
			PortOutput output = entry.getValue();
			// The same coercion the wire applies, so the stored values are exactly what a downstream node received.
			PortPayload payload = output.toPayload("reuse", 0);
			JsonArray values = new JsonArray();
			if (payload != null) {
				for (Object value : normaliseMedia(payload.values(), mediaPath)) {
					values.add(value);
				}
			}
			stored.put(entry.getKey(), new JsonObject()
				.put("contentType", output.port().contentType())
				.put("cardinality", output.port().cardinality().name())
				.put("values", values));
		}
		String encoded = stored.encode();
		if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_STORED_BYTES) {
			return null;
		}
		return stored;
	}

	/**
	 * Turn a stored output back into port outputs.
	 *
	 * @param stored    the stored form
	 * @param mediaPath the item's own media path, put back for {@link #MEDIA_PLACEHOLDER}
	 * @return the port outputs, or null when the stored form is unusable here - malformed, or pointing at a file this worker does not have
	 */
	public static Map<String, PortOutput> decode(JsonObject stored, String mediaPath) {
		if (stored == null) {
			return null;
		}
		Map<String, PortOutput> outputs = new LinkedHashMap<>();
		for (String portId : stored.fieldNames()) {
			Object raw = stored.getValue(portId);
			if (!(raw instanceof JsonObject port)) {
				return null;
			}
			String contentType = port.getString("contentType");
			String cardinality = port.getString("cardinality");
			JsonArray values = port.getJsonArray("values");
			if (contentType == null || cardinality == null || values == null) {
				return null;
			}
			Cardinality card;
			try {
				card = Cardinality.valueOf(cardinality);
			} catch (IllegalArgumentException e) {
				return null;
			}
			boolean fileValued = isFileValued(contentType);
			List<Object> plain = new ArrayList<>(values.size());
			for (Object value : values) {
				Object restored = toPlain(value);
				if (MEDIA_PLACEHOLDER.equals(restored)) {
					restored = mediaPath;
				} else if (fileValued && restored instanceof String path && path.startsWith("/") && !Files.exists(Path.of(path))) {
					// Produced bytes live on the worker that made them. Another worker - or this one after a cleanup - cannot hand the path on.
					return null;
				}
				plain.add(restored);
			}
			outputs.put(portId, new PortOutput(new OutputPort<>(portId, contentType, card, Object.class), plain));
		}
		return outputs;
	}

	/** Media and artifact values are file paths on the wire. */
	private static boolean isFileValued(String contentType) {
		String family = ContentTypeLattice.family(contentType);
		return "media".equals(family) || "artifact".equals(family);
	}

	private static List<Object> normaliseMedia(List<Object> values, String mediaPath) {
		if (values == null) {
			return List.of();
		}
		List<Object> out = new ArrayList<>(values.size());
		for (Object value : values) {
			out.add(mediaPath != null && mediaPath.equals(value) ? MEDIA_PLACEHOLDER : value);
		}
		return out;
	}

	private static JsonObject without(JsonObject json, Set<String> keys) {
		JsonObject copy = json.copy();
		keys.forEach(copy::remove);
		return copy;
	}

	/**
	 * A key-order-independent, type-normalised copy: maps become sorted, integral numbers become longs. Two option objects that mean the same thing
	 * must hash the same whatever order Jackson wrote them in or whether a count arrived as an int or a long.
	 */
	static Object canonical(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof JsonObject json) {
			return canonical(json.getMap());
		}
		if (value instanceof JsonArray array) {
			return canonical(array.getList());
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
			return sorted;
		}
		if (value instanceof Collection<?> collection) {
			List<Object> list = new ArrayList<>(collection.size());
			collection.forEach(v -> list.add(canonical(v)));
			return list;
		}
		if (value instanceof Object[] array) {
			return canonical(Arrays.asList(array));
		}
		if (value instanceof Number number) {
			double d = number.doubleValue();
			if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
				return number.longValue();
			}
			return d;
		}
		if (value instanceof Boolean || value instanceof String) {
			return value;
		}
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		return value.toString();
	}

	/** Vert.x hands nested JSON back as its own wrapper types; the coercer and every downstream reader expect plain maps and lists. */
	static Object toPlain(Object value) {
		if (value instanceof JsonObject json) {
			return toPlain(json.getMap());
		}
		if (value instanceof JsonArray array) {
			return toPlain(array.getList());
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> plain = new LinkedHashMap<>();
			map.forEach((k, v) -> plain.put(String.valueOf(k), toPlain(v)));
			return plain;
		}
		if (value instanceof Collection<?> collection) {
			List<Object> plain = new ArrayList<>(collection.size());
			collection.forEach(v -> plain.add(toPlain(v)));
			return plain;
		}
		return value;
	}

	private static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
