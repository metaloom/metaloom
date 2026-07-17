package io.metaloom.cortex.api.option;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public class CortexOptions {

	private Map<String, CortexNodeOptions> nodes = new HashMap<>();
	private LoomClientOptions loom = new LoomClientOptions();
	private boolean dryrun;

	private Path metaPath;

	private int monitoringPort = 8093;

	// Maximum number of media items to process concurrently (media-level concurrency)
	private int maxConcurrentMedia = 4;

	// Default timeout values for different node types (in milliseconds)
	private static final Map<String, Long> DEFAULT_TIMEOUTS = createDefaultTimeouts();

	private static Map<String, Long> createDefaultTimeouts() {
		Map<String, Long> map = new HashMap<>();
		map.put("sha512", 30000L);
		map.put("sha256", 30000L);
		map.put("md5", 30000L);
		map.put("chunk-hash", 60000L);
		map.put("thumbnail", 120000L);
		map.put("facedetect", 300000L);
		map.put("fingerprint", 300000L);
		map.put("ocr", 300000L);
		map.put("tika", 120000L);
		map.put("whisper", 600000L);
		map.put("llm", 600000L);
		map.put("captioning", 300000L);
		map.put("scene-detection", 300000L);
		map.put("quality", 60000L);
		map.put("dedup", 60000L);
		map.put("consistency", 60000L);
		map.put("loom-sync", 60000L);
		return Map.copyOf(map);
	}

	public CortexOptions() {
	}

	public Map<String, CortexNodeOptions> getNodes() {
		return nodes;
	}

	public CortexOptions setNodes(Map<String, CortexNodeOptions> nodes) {
		this.nodes = nodes;
		return this;
	}

	public LoomClientOptions getLoom() {
		return loom;
	}

	public CortexOptions setLoom(LoomClientOptions loom) {
		this.loom = loom;
		return this;
	}

	public boolean isDryrun() {
		return dryrun;
	}

	public CortexOptions setDryrun(boolean dryrun) {
		this.dryrun = dryrun;
		return this;
	}

	/**
	 * Basepath for the metadata storage files.
	 * 
	 * @return
	 */
	public Path getMetaPath() {
		return metaPath;
	}

	public CortexOptions setMetaPath(Path metaPath) {
		this.metaPath = metaPath;
		return this;
	}

	public int getMonitoringPort() {
		return monitoringPort;
	}

	public CortexOptions setMonitoringPort(int monitoringPort) {
		this.monitoringPort = monitoringPort;
		return this;
	}

	/**
	 * Get the maximum number of media items to process concurrently.
	 * This controls media-level concurrency in the pipeline executor.
	 * 
	 * @return max concurrent media items (default: 4)
	 */
	public int getMaxConcurrentMedia() {
		return maxConcurrentMedia;
	}

	/**
	 * Set the maximum number of media items to process concurrently.
	 * 
	 * @param maxConcurrentMedia max concurrent media items
	 * @return this for chaining
	 */
	public CortexOptions setMaxConcurrentMedia(int maxConcurrentMedia) {
		this.maxConcurrentMedia = maxConcurrentMedia;
		return this;
	}

	/**
	 * Get the default timeout for a specific node type.
	 * Returns 0 (no timeout) if no default is configured for the node type.
	 * 
	 * @param nodeType the node type identifier (e.g., "sha512", "whisper", "llm")
	 * @return default timeout in milliseconds, or 0 if not configured
	 */
	public long getDefaultTimeoutMs(String nodeType) {
		return DEFAULT_TIMEOUTS.getOrDefault(nodeType, 0L);
	}

	/**
	 * Get all default timeout configurations.
	 * 
	 * @return unmodifiable map of node type -> default timeout in milliseconds
	 */
	public Map<String, Long> getDefaultTimeouts() {
		return Map.copyOf(DEFAULT_TIMEOUTS);
	}

}
