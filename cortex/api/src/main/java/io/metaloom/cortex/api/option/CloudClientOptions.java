package io.metaloom.cortex.api.option;

/**
 * Settings shared by every cloud-drive provider (Google Drive, OneDrive).
 *
 * <p>Like {@link S3ClientOptions} these are <em>worker-level</em>: they describe this machine's
 * environment and its credentials rather than the work. Credentials must never move onto a node
 * definition - a pipeline definition is stored in Postgres and rendered verbatim in the pipeline
 * editor, and {@code ParameterType} has no {@code SECRET} value, so a credential parameter would
 * be displayed as plain text.</p>
 *
 * <p><b>Deployment note:</b> cloud media is materialized lazily by whichever worker runs a node
 * task, so <em>every</em> worker touching {@code gdrive://} or {@code onedrive://} media needs
 * these settings - not only the one running the source node.</p>
 *
 * @param <T> the concrete provider options type, for fluent setters
 */
public abstract class CloudClientOptions<T extends CloudClientOptions<T>> {

	/** Same 50 GiB default the S3 cache uses. */
	public static final long DEFAULT_MAX_CACHE_BYTES = 50L * 1024 * 1024 * 1024;

	/**
	 * 24 hours, deliberately longer than the S3 default of 6.
	 *
	 * <p>A cloud delta feed is a provider <em>guarantee</em> about content rather than a
	 * best-effort notification that can be lost in transit, so the reconcile pass is not needed to
	 * make change detection correct. It survives for one narrower reason: the delta feed is
	 * drive-wide, so deciding whether a changed file is inside the selected folder subtree is an
	 * approximation (see {@code CloudDifferentialScanner}). A full walk repairs that.</p>
	 */
	public static final long DEFAULT_RECONCILE_INTERVAL_MS = 24L * 60 * 60 * 1000;

	public static final long DEFAULT_REQUEST_TIMEOUT_MS = 60_000;

	public static final int DEFAULT_MAX_RETRIES = 5;

	private String cachePath;
	private String indexPath;
	private long maxCacheBytes = DEFAULT_MAX_CACHE_BYTES;
	private long maxObjectSize = 0;
	private long reconcileIntervalMs = DEFAULT_RECONCILE_INTERVAL_MS;
	private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
	private int maxRetries = DEFAULT_MAX_RETRIES;
	private String defaultDriveId;

	protected abstract T self();

	/**
	 * Whether this provider is configured well enough to build a client.
	 *
	 * @return true when credentials are present
	 */
	public abstract boolean isConfigured();

	/**
	 * Why a half-filled credential set is unusable.
	 *
	 * <p>Returning a reason rather than silently staying inactive matters: an inactive provider
	 * looks to Loom like a missing capability, and a run dispatched against it dies mid-flight
	 * instead of being rejected. A deployment that set a client id but forgot the secret should
	 * hear about it at boot.</p>
	 *
	 * @return the problem, naming the flag to set, or null when the config is coherent (which
	 *         includes "nothing configured at all")
	 */
	public abstract String partialConfigurationReason();

	/**
	 * @return explicit cache directory, or null to derive it from the meta path
	 */
	public String getCachePath() {
		return cachePath;
	}

	public T setCachePath(String cachePath) {
		this.cachePath = cachePath;
		return self();
	}

	/**
	 * @return explicit index directory, or null to derive it from the meta path
	 */
	public String getIndexPath() {
		return indexPath;
	}

	public T setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return self();
	}

	public long getMaxCacheBytes() {
		return maxCacheBytes;
	}

	public T setMaxCacheBytes(long maxCacheBytes) {
		this.maxCacheBytes = maxCacheBytes;
		return self();
	}

	/**
	 * @return the largest file to materialize in bytes, or 0 for unbounded
	 */
	public long getMaxObjectSize() {
		return maxObjectSize;
	}

	public T setMaxObjectSize(long maxObjectSize) {
		this.maxObjectSize = maxObjectSize;
		return self();
	}

	/**
	 * @return how long the delta fast path may be trusted before a full walk is forced
	 */
	public long getReconcileIntervalMs() {
		return reconcileIntervalMs;
	}

	public T setReconcileIntervalMs(long reconcileIntervalMs) {
		this.reconcileIntervalMs = reconcileIntervalMs;
		return self();
	}

	public long getRequestTimeoutMs() {
		return requestTimeoutMs;
	}

	public T setRequestTimeoutMs(long requestTimeoutMs) {
		this.requestTimeoutMs = requestTimeoutMs;
		return self();
	}

	/**
	 * @return how often a throttled or failed request is retried before giving up
	 */
	public int getMaxRetries() {
		return maxRetries;
	}

	public T setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
		return self();
	}

	/**
	 * Drive used when a node definition does not name one.
	 *
	 * @return the drive id, or null
	 */
	public String getDefaultDriveId() {
		return defaultDriveId;
	}

	public T setDefaultDriveId(String defaultDriveId) {
		this.defaultDriveId = defaultDriveId;
		return self();
	}

	protected static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}
}
