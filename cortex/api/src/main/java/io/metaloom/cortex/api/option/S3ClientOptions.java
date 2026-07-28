package io.metaloom.cortex.api.option;

/**
 * Worker-level S3 connection and cache configuration.
 *
 * <p>These settings describe the <em>worker's environment</em>, not the work, so per
 * {@code NODES.md} section 5.1 they live here rather than on a pipeline node definition. That
 * split is also what keeps credentials out of the pipeline JSON stored in Postgres and rendered
 * in the pipeline editor - which matters because {@code ParameterType} has no
 * {@code SECRET}/{@code PASSWORD} value, so a credential parameter would render as plain text.</p>
 *
 * <p><b>Deployment note:</b> because S3 media is materialized lazily by whichever worker runs a
 * node task, <em>every</em> worker touching S3 media needs these settings - not only the one
 * running the {@code s3-source} node.</p>
 */
public class S3ClientOptions {

	public static final String DEFAULT_REGION = "us-east-1";
	public static final long DEFAULT_MAX_CACHE_BYTES = 50L * 1024 * 1024 * 1024;
	public static final long DEFAULT_RECONCILE_INTERVAL_MS = 6L * 60 * 60 * 1000;

	private String endpoint;
	private String region = DEFAULT_REGION;
	private String accessKey;
	private String secretKey;
	private Boolean pathStyleAccess;

	private String cachePath;
	private String indexPath;
	private long maxCacheBytes = DEFAULT_MAX_CACHE_BYTES;
	private long maxObjectSize = 0;
	private long reconcileIntervalMs = DEFAULT_RECONCILE_INTERVAL_MS;

	private S3EventOptions events = new S3EventOptions();

	/**
	 * @return true when enough is configured to build a client. An endpoint-less config is still
	 *         valid - that is plain AWS with credentials from the default provider chain.
	 */
	public boolean isConfigured() {
		return endpoint != null && !endpoint.isBlank()
			|| accessKey != null && !accessKey.isBlank()
			|| region != null && !region.isBlank();
	}

	/**
	 * Path-style access is required by MinIO and most S3-compatible gateways, and is the wrong
	 * default for real AWS. Rather than force every MinIO deployment to remember the flag, it
	 * defaults to "on when a custom endpoint is set" and can still be overridden explicitly.
	 *
	 * @return whether to use path-style bucket addressing
	 */
	public boolean isPathStyleAccess() {
		if (pathStyleAccess != null) {
			return pathStyleAccess;
		}
		return endpoint != null && !endpoint.isBlank();
	}

	public String getEndpoint() {
		return endpoint;
	}

	public S3ClientOptions setEndpoint(String endpoint) {
		this.endpoint = endpoint;
		return this;
	}

	public String getRegion() {
		return region;
	}

	public S3ClientOptions setRegion(String region) {
		this.region = region;
		return this;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public S3ClientOptions setAccessKey(String accessKey) {
		this.accessKey = accessKey;
		return this;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public S3ClientOptions setSecretKey(String secretKey) {
		this.secretKey = secretKey;
		return this;
	}

	public Boolean getPathStyleAccess() {
		return pathStyleAccess;
	}

	public S3ClientOptions setPathStyleAccess(Boolean pathStyleAccess) {
		this.pathStyleAccess = pathStyleAccess;
		return this;
	}

	/**
	 * @return explicit cache directory, or null to derive it from the meta path ({@code s3_bin})
	 */
	public String getCachePath() {
		return cachePath;
	}

	public S3ClientOptions setCachePath(String cachePath) {
		this.cachePath = cachePath;
		return this;
	}

	/**
	 * @return explicit index directory, or null to derive it from the meta path ({@code s3-index})
	 */
	public String getIndexPath() {
		return indexPath;
	}

	public S3ClientOptions setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return this;
	}

	public long getMaxCacheBytes() {
		return maxCacheBytes;
	}

	public S3ClientOptions setMaxCacheBytes(long maxCacheBytes) {
		this.maxCacheBytes = maxCacheBytes;
		return this;
	}

	/**
	 * @return the largest object to materialize in bytes, or 0 for unbounded
	 */
	public long getMaxObjectSize() {
		return maxObjectSize;
	}

	public S3ClientOptions setMaxObjectSize(long maxObjectSize) {
		this.maxObjectSize = maxObjectSize;
		return this;
	}

	/**
	 * How long the event fast path may be trusted before a full listing is forced.
	 *
	 * <p>Bucket notifications are at-least-once and <em>can be lost</em>, so this reconcile pass
	 * is what makes the event path correct rather than merely fast.</p>
	 *
	 * @return the reconcile interval in milliseconds
	 */
	public long getReconcileIntervalMs() {
		return reconcileIntervalMs;
	}

	public S3ClientOptions setReconcileIntervalMs(long reconcileIntervalMs) {
		this.reconcileIntervalMs = reconcileIntervalMs;
		return this;
	}

	public S3EventOptions getEvents() {
		return events;
	}

	public S3ClientOptions setEvents(S3EventOptions events) {
		this.events = events == null ? new S3EventOptions() : events;
		return this;
	}
}
