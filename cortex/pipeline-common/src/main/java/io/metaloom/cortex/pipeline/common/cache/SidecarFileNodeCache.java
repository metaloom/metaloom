package io.metaloom.cortex.pipeline.common.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Persistent cache backed by sidecar JSON files stored in a segmented directory structure.
 *
 * <p>File layout: {@code {basePath}/{nodeId}/{hash_segment}/{sha512}.json}
 *
 * <p>This cache is suitable for all output sizes. It stores the node result output map
 * as a simple key=value text file. For binary outputs, nodes should write binary data
 * to a separate file and store the path reference in the output map.</p>
 */
public class SidecarFileNodeCache implements NodeCacheProvider {

	private static final Logger log = LoggerFactory.getLogger(SidecarFileNodeCache.class);

	private final Path basePath;

	/**
	 * @param basePath the root directory for sidecar cache files (e.g. CortexOptions.getMetaPath())
	 */
	public SidecarFileNodeCache(Path basePath) {
		this.basePath = basePath;
	}

	@Override
	public Optional<NodeResult> get(String nodeId, LoomMedia media) {
		try {
			Path file = resolveCachePath(nodeId, media);
			if (!Files.exists(file)) {
				return Optional.empty();
			}
			String raw = Files.readString(file, StandardCharsets.UTF_8);
			if (raw == null || raw.isEmpty()) {
				return Optional.empty();
			}
			Map<String, Object> output = XAttrNodeCache.deserializeOutputMap(raw);
			return Optional.of(NodeResult.success(nodeId, 0, output));
		} catch (Exception e) {
			log.debug("Failed to read sidecar cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void put(String nodeId, LoomMedia media, NodeResult result) {
		if (result.getState() != NodeState.COMPLETED) {
			return;
		}
		try {
			Path file = resolveCachePath(nodeId, media);
			Files.createDirectories(file.getParent());
			String serialized = XAttrNodeCache.serializeOutputMap(result.getOutput());
			Files.writeString(file, serialized, StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("Failed to write sidecar cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
		}
	}

	@Override
	public void invalidate(String nodeId, LoomMedia media) {
		try {
			Path file = resolveCachePath(nodeId, media);
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.debug("Failed to invalidate sidecar cache for node {} on {}: {}", nodeId, media.absolutePath(), e.getMessage());
		}
	}

	@Override
	public void clear() {
		log.warn("clear() for SidecarFileNodeCache would require recursive directory delete — not implemented");
	}

	private Path resolveCachePath(String nodeId, LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".cache";
		Path nodeDir = basePath.resolve("node-cache").resolve(nodeId);
		Path segmented = HashUtils.segmentPath(nodeDir, hash);
		return segmented.resolve(fileName);
	}
}
