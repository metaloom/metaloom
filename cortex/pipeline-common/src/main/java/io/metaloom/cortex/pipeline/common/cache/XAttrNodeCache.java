package io.metaloom.cortex.pipeline.common.cache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.utils.fs.XAttrUtils;

/**
 * Persistent cache backed by filesystem extended attributes (xattr). Each node's result
 * is stored as a JSON-encoded xattr on the media file itself.
 *
 * <p>XAttr key format: {@code loom_cache_{nodeId}}
 *
 * <p>This cache is suitable for small output values (strings, numbers, flags).
 * For large outputs (binary data, Avro), use {@link SidecarFileNodeCache} instead.
 *
 * <p>Note: xattr has a size limit (typically 64KB on ext4). The cache stores
 * only the output map serialized as a simple key=value format.</p>
 */
public class XAttrNodeCache implements NodeCacheProvider {

	private static final Logger log = LoggerFactory.getLogger(XAttrNodeCache.class);

	private static final String PREFIX = "loom_cache_";

	@Override
	public Optional<NodeResult> get(String nodeId, LoomMedia media) {
		try {
			Path path = media.path();
			String attrKey = attrKey(nodeId);
			if (!XAttrUtils.hasXAttr(path, attrKey)) {
				return Optional.empty();
			}
			String raw = XAttrUtils.readXAttr(path, attrKey, String.class);
			if (raw == null || raw.isEmpty()) {
				return Optional.empty();
			}
			Map<String, PortOutput> output = deserializeOutputMap(raw);
			return Optional.of(NodeResult.success(nodeId, 0, output));
		} catch (Exception e) {
			log.debug("Failed to read xattr cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		if (result.getState() != ResultState.SUCCESS) {
			return;
		}
		try {
			Path path = media.path();
			String attrKey = attrKey(nodeId);
			String serialized = serializeOutputMap(result.getOutputs());
			XAttrUtils.writeXAttr(path, attrKey, serialized);
		} catch (Exception e) {
			log.warn("Failed to write xattr cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
		}
	}

	@Override
	public void invalidate(String nodeId, LoomMedia media) {
		try {
			Path path = media.path();
			String attrKey = attrKey(nodeId);
			// Write empty to effectively invalidate
			XAttrUtils.writeXAttr(path, attrKey, "");
		} catch (Exception e) {
			log.debug("Failed to invalidate xattr cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
		}
	}

	@Override
	public void clear() {
		// Cannot enumerate all files — no-op for xattr-based cache
		log.warn("clear() is not supported for XAttrNodeCache (would require file system scan)");
	}

	private String attrKey(String nodeId) {
		return PREFIX + nodeId;
	}

	/**
	 * Serialize the port outputs to a line-based format: one line per emitted element,
	 * {@code portId TAB contentType TAB cardinality TAB value}.
	 *
	 * <p>
	 * The declared content type travels with the value because a {@link PortOutput} cannot be
	 * rebuilt without it — the port, not the node id, is what the boundary coerces against. The
	 * cardinality is carried for the same reason: a {@code MANY} port writes one line per element
	 * and must come back as a sequence even when it happened to emit exactly one.
	 * </p>
	 *
	 * <p>
	 * Values are stringified, as this cache always did; {@code ValueCoercer} widens them back on
	 * read. A {@code null} element is dropped rather than stored, since a port never carries null.
	 * </p>
	 */
	static String serializeOutputMap(Map<String, PortOutput> output) {
		if (output == null || output.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, PortOutput> entry : output.entrySet()) {
			PortOutput portOutput = entry.getValue();
			if (portOutput == null || portOutput.port() == null) {
				continue;
			}
			OutputPort<?> port = portOutput.port();
			for (Object value : portOutput.values()) {
				if (value == null) {
					continue;
				}
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(escape(entry.getKey())).append('\t')
					.append(escape(port.contentType())).append('\t')
					.append(port.isMany() ? "MANY" : "ONE").append('\t')
					.append(escape(value.toString()));
			}
		}
		return sb.length() == 0 ? "{}" : sb.toString();
	}

	/**
	 * Deserialize the line-based format back to port outputs, preserving element order.
	 */
	static Map<String, PortOutput> deserializeOutputMap(String raw) {
		if (raw == null || raw.isEmpty() || "{}".equals(raw)) {
			return Collections.emptyMap();
		}
		Map<String, OutputPort<String>> ports = new LinkedHashMap<>();
		Map<String, List<Object>> values = new LinkedHashMap<>();
		for (String line : raw.split("\n")) {
			String[] parts = line.split("\t", 4);
			if (parts.length < 4) {
				// Not a port line (e.g. a cache file written before ports existed) — ignore it
				// rather than resurrect it as a value on a port we cannot name a type for.
				continue;
			}
			String portId = unescape(parts[0]);
			String contentType = unescape(parts[1]);
			boolean many = "MANY".equals(parts[2]);
			ports.computeIfAbsent(portId, id -> many
				? OutputPort.many(id, contentType, String.class)
				: OutputPort.one(id, contentType, String.class));
			values.computeIfAbsent(portId, id -> new ArrayList<>(1)).add(unescape(parts[3]));
		}
		if (ports.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, PortOutput> map = new LinkedHashMap<>(ports.size());
		ports.forEach((id, port) -> map.put(id, new PortOutput(port, List.copyOf(values.get(id)))));
		return map;
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
	}

	/**
	 * Single pass, left to right — chained {@code String.replace} calls cannot undo this escaping
	 * correctly, because a literal backslash followed by 't' is indistinguishable from an escaped
	 * tab once the backslash pair has already been collapsed.
	 */
	private static String unescape(String value) {
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\' && i + 1 < value.length()) {
				char next = value.charAt(++i);
				switch (next) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case '\\' -> sb.append('\\');
					default -> sb.append('\\').append(next);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
