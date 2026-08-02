package io.metaloom.cortex.api.option;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public class CortexOptions {

	private Map<String, CortexNodeOptions> nodes = new HashMap<>();
	private LoomClientOptions loom = new LoomClientOptions();

	/**
	 * S3 connection, cache and event settings.
	 *
	 * <p>Worker-level rather than per pipeline node, because they describe this machine's
	 * environment rather than the work (see the node spec's per-instance-configuration section).
	 * That also keeps credentials out of the pipeline definition, which is stored in Postgres and
	 * rendered in the pipeline editor.</p>
	 *
	 * <p>Needed by <em>every</em> worker that touches S3 media, not only the one running the
	 * {@code s3-source} node: media is materialized lazily by whichever worker runs a node task.</p>
	 */
	private S3ClientOptions s3 = new S3ClientOptions();

	/**
	 * Google Drive credentials, connection and cache settings.
	 *
	 * <p>Worker-level for the same reason as {@link #s3}, with one extra edge: {@code ParameterType}
	 * has no {@code SECRET} value, so an OAuth client secret or a service-account key placed on a
	 * node definition would be persisted in Postgres and rendered as plain text in the editor.</p>
	 */
	private GDriveClientOptions gdrive = new GDriveClientOptions();

	/**
	 * OneDrive / SharePoint credentials, connection and cache settings. Worker-level, see
	 * {@link #gdrive}.
	 */
	private OneDriveClientOptions onedrive = new OneDriveClientOptions();

	private boolean dryrun;

	private Path metaPath;

	private int monitoringPort = 8093;

	/**
	 * Node kinds this worker is willing to execute (the whitelist).
	 *
	 * <p>Null or empty means "anything", which keeps a worker that predates
	 * whitelisting in the pool rather than silently dropping it out. Setting it lets a
	 * deployment dedicate machines to particular work - GPU boxes to embeddings, the
	 * one host with the media mounted to filesystem scanning.</p>
	 */
	private Set<String> nodeWhitelist;

	/**
	 * Node kinds this worker refuses to execute (the blacklist).
	 *
	 * <p>Complements the whitelist so a machine can accept everything except a couple
	 * of expensive kinds without enumerating the whole allow-list. A blacklisted kind
	 * is refused even when the whitelist would admit it.</p>
	 */
	private Set<String> nodeBlacklist;

	/**
	 * Identity this worker registers under.
	 *
	 * <p>Generated per process when unset, which means a restarted worker is a
	 * different worker as far as Loom is concerned - leases, attribution and the
	 * in-flight count all key on this. Setting it makes the identity survive a
	 * restart.</p>
	 */
	private String nodeId;

	/**
	 * How long a shutting-down worker lets its running tasks finish before handing them
	 * back to Loom.
	 *
	 * <p>The default matches Kubernetes' 30 second termination grace period: waiting
	 * longer than the orchestrator will wait achieves nothing, because the process is
	 * killed mid-drain and the tasks fall back to lease expiry anyway. A deployment that
	 * runs minutes-long nodes (transcription, OCR) should raise this <em>and</em> the
	 * matching grace period together, or accept that those tasks are re-placed rather
	 * than finished.</p>
	 */
	private long drainTimeoutMs = 30_000;

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
		map.put("vlm", 600000L);
		// A chunked transcript is several sequential model calls, so it needs the LLM budget, not a short one.
		map.put("translate", 600000L);
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

	public S3ClientOptions getS3() {
		return s3;
	}

	public CortexOptions setS3(S3ClientOptions s3) {
		this.s3 = s3 == null ? new S3ClientOptions() : s3;
		return this;
	}

	public GDriveClientOptions getGdrive() {
		return gdrive;
	}

	public CortexOptions setGdrive(GDriveClientOptions gdrive) {
		this.gdrive = gdrive == null ? new GDriveClientOptions() : gdrive;
		return this;
	}

	public OneDriveClientOptions getOnedrive() {
		return onedrive;
	}

	public CortexOptions setOnedrive(OneDriveClientOptions onedrive) {
		this.onedrive = onedrive == null ? new OneDriveClientOptions() : onedrive;
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

	/**
	 * @return the accepted node kinds (whitelist), or null/empty when the worker accepts anything
	 */
	public Set<String> getNodeWhitelist() {
		return nodeWhitelist;
	}

	public CortexOptions setNodeWhitelist(Set<String> nodeWhitelist) {
		this.nodeWhitelist = nodeWhitelist;
		return this;
	}

	/**
	 * @return the refused node kinds (blacklist), or null/empty when the worker refuses nothing
	 */
	public Set<String> getNodeBlacklist() {
		return nodeBlacklist;
	}

	public CortexOptions setNodeBlacklist(Set<String> nodeBlacklist) {
		this.nodeBlacklist = nodeBlacklist;
		return this;
	}

	/**
	 * @return the configured worker identity, or null to generate one per process
	 */
	public String getNodeId() {
		return nodeId;
	}

	public CortexOptions setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public long getDrainTimeoutMs() {
		return drainTimeoutMs;
	}

	/**
	 * @param drainTimeoutMs how long a shutdown waits for running tasks; 0 hands every
	 *                       in-flight task straight back
	 */
	public CortexOptions setDrainTimeoutMs(long drainTimeoutMs) {
		this.drainTimeoutMs = Math.max(0, drainTimeoutMs);
		return this;
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
