package io.metaloom.cortex.pipeline.common.cache;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
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
			Map<String, Object> output = deserializeOutputMap(raw);
			return Optional.of(NodeResult.success(nodeId, 0, output));
		} catch (Exception e) {
			log.debug("Failed to read xattr cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		if (result.getState() != NodeState.COMPLETED) {
			return;
		}
		try {
			Path path = media.path();
			String attrKey = attrKey(nodeId);
			String serialized = serializeOutputMap(result.getOutput());
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
	 * Serialize output map to a simple line-based format: key=value per line.
	 * Null values are stored as the literal string "null".
	 */
	static String serializeOutputMap(Map<String, Object> output) {
		if (output == null || output.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : output.entrySet()) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			String value = entry.getValue() != null ? entry.getValue().toString() : "null";
			// Escape newlines in values
			value = value.replace("\\", "\\\\").replace("\n", "\\n");
			sb.append(entry.getKey()).append('=').append(value);
		}
		return sb.toString();
	}

	/**
	 * Deserialize the line-based format back to a map.
	 */
	static Map<String, Object> deserializeOutputMap(String raw) {
		if (raw == null || raw.isEmpty() || "{}".equals(raw)) {
			return Collections.emptyMap();
		}
		Map<String, Object> map = new HashMap<>();
		for (String line : raw.split("\n")) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				String key = line.substring(0, eq);
				String value = line.substring(eq + 1);
				// Unescape
				value = value.replace("\\n", "\n").replace("\\\\", "\\");
				map.put(key, "null".equals(value) ? null : value);
			}
		}
		return map;
	}
}
