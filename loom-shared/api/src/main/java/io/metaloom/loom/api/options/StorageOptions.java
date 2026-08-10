package io.metaloom.loom.api.options;

/**
 * Options which control where uploaded asset binaries are persisted, and what Loom refuses to accept.
 *
 * <p>
 * This directory is only the <em>default</em> destination. A library that points at an {@code asset_pool} stores its binaries in that pool instead;
 * see {@code spec/features/rest/REST_BINARY_HANDLING.md}. Libraries with no pool — which is every library on an installation that has not configured
 * one — land here.
 * </p>
 */
public class StorageOptions implements Option {

	public static final String DEFAULT_UPLOAD_DIRECTORY = "data/storage";

	/** No cap. Matches the historic behaviour of {@code BodyHandler.setBodyLimit(-1)}. */
	public static final long DEFAULT_MAX_UPLOAD_SIZE = -1L;

	/** 1 GiB. Enough headroom that Postgres and the logs do not die alongside the upload that filled the volume. */
	public static final long DEFAULT_MIN_FREE_SPACE = 1024L * 1024 * 1024;

	/**
	 * 5 GiB. Five times the critical mark, so the amber state has room to be noticed before uploads start failing.
	 */
	public static final long DEFAULT_WARN_FREE_SPACE = 5L * 1024 * 1024 * 1024;

	/** Five minutes. Disk fills over hours, and this only writes a log line. */
	public static final long DEFAULT_SPACE_CHECK_INTERVAL_MS = 300_000L;

	@EnvironmentVariable(name = "LOOM_STORAGE_UPLOAD_DIR", description = "Override the directory in which uploaded asset binaries are stored. Also accepted as LOOM_BINARY_DIR.")
	private String uploadDirectory = DEFAULT_UPLOAD_DIRECTORY;

	@EnvironmentVariable(name = "LOOM_STORAGE_MAX_UPLOAD_SIZE", description = "Largest accepted upload in bytes. -1 disables the cap.")
	private long maxUploadSize = DEFAULT_MAX_UPLOAD_SIZE;

	@EnvironmentVariable(name = "LOOM_STORAGE_MIN_FREE_SPACE", description = "Refuse uploads once the target filesystem would drop below this many free bytes. 0 disables the check. Not applicable to S3 pools.")
	private long minFreeSpace = DEFAULT_MIN_FREE_SPACE;

	@EnvironmentVariable(name = "LOOM_STORAGE_WARN_FREE_SPACE", description = "Report a backend as degraded once it has fewer than this many free bytes. Never refuses anything. 0 disables the warning level. Not applicable to S3 pools.")
	private long warnFreeSpace = DEFAULT_WARN_FREE_SPACE;

	@EnvironmentVariable(name = "LOOM_STORAGE_SPACE_CHECK_INTERVAL_MS", description = "How often to check every storage backend's free space and log a warning when it crosses a watermark. 0 disables the periodic check.")
	private long spaceCheckIntervalMs = DEFAULT_SPACE_CHECK_INTERVAL_MS;

	public String getUploadDirectory() {
		return uploadDirectory;
	}

	public StorageOptions setUploadDirectory(String uploadDirectory) {
		this.uploadDirectory = uploadDirectory;
		return this;
	}

	public long getMaxUploadSize() {
		return maxUploadSize;
	}

	public StorageOptions setMaxUploadSize(long maxUploadSize) {
		this.maxUploadSize = maxUploadSize;
		return this;
	}

	public long getMinFreeSpace() {
		return minFreeSpace;
	}

	public StorageOptions setMinFreeSpace(long minFreeSpace) {
		this.minFreeSpace = minFreeSpace;
		return this;
	}

	/**
	 * The high watermark: below this a backend is reported as degraded, but nothing is refused.
	 *
	 * <p>
	 * There is deliberately no second "critical" setting. {@link #getMinFreeSpace()} already is the critical mark — it is what produces the 507 — and
	 * introducing a synonym for it would mean two names for one threshold, one of which quietly does nothing.
	 * </p>
	 */
	public long getWarnFreeSpace() {
		return warnFreeSpace;
	}

	public StorageOptions setWarnFreeSpace(long warnFreeSpace) {
		this.warnFreeSpace = warnFreeSpace;
		return this;
	}

	public long getSpaceCheckIntervalMs() {
		return spaceCheckIntervalMs;
	}

	public StorageOptions setSpaceCheckIntervalMs(long spaceCheckIntervalMs) {
		this.spaceCheckIntervalMs = spaceCheckIntervalMs;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.notBlank("uploadDirectory", uploadDirectory);
		if (maxUploadSize == 0 || maxUploadSize < -1) {
			errors.add("maxUploadSize", "The maximum upload size (LOOM_STORAGE_MAX_UPLOAD_SIZE) must be positive, or -1 for no limit.");
		}
		if (minFreeSpace < 0) {
			errors.add("minFreeSpace", "The minimum free space (LOOM_STORAGE_MIN_FREE_SPACE) must not be negative. Use 0 to disable the check.");
		}
		if (warnFreeSpace < 0) {
			errors.add("warnFreeSpace", "The warning free space (LOOM_STORAGE_WARN_FREE_SPACE) must not be negative. Use 0 to disable the warning level.");
		}
		// A warning mark at or below the refusal mark can never fire before the refusal does, so the amber state
		// would be configuration that does nothing. Rejecting it at startup beats reporting a backend as healthy
		// right up to the moment it starts answering 507.
		if (warnFreeSpace > 0 && minFreeSpace > 0 && warnFreeSpace < minFreeSpace) {
			errors.add("warnFreeSpace", "The warning free space (LOOM_STORAGE_WARN_FREE_SPACE=" + warnFreeSpace
				+ ") must be at least the minimum free space (LOOM_STORAGE_MIN_FREE_SPACE=" + minFreeSpace
				+ "), otherwise uploads are refused before anything is ever reported as degraded.");
		}
		if (spaceCheckIntervalMs < 0) {
			errors.add("spaceCheckIntervalMs",
				"The storage space check interval (LOOM_STORAGE_SPACE_CHECK_INTERVAL_MS) must not be negative. Use 0 to disable the check.");
		}
	}

	@Override
	public void overrideWithEnv() {
		// LOOM_BINARY_DIR is applied first so that LOOM_STORAGE_UPLOAD_DIR wins when both are set.
		//
		// It is honoured at all because helm/loom has been setting it — and only it — since the chart
		// was written, against a process that only ever read LOOM_STORAGE_UPLOAD_DIR. Every
		// Kubernetes install therefore wrote uploads into the container's ephemeral filesystem while
		// its /uploads PersistentVolumeClaim sat empty, and lost them on the next restart. Renaming
		// the chart variable alone would fix new installs and silently keep breaking upgrades whose
		// values.yaml still carries the old name, so the process accepts both.
		OptionUtils.applyEnv("LOOM_BINARY_DIR", this::setUploadDirectory);
		OptionUtils.applyEnv("LOOM_STORAGE_UPLOAD_DIR", this::setUploadDirectory);
		OptionUtils.applyEnvLong("LOOM_STORAGE_MAX_UPLOAD_SIZE", this::setMaxUploadSize);
		OptionUtils.applyEnvLong("LOOM_STORAGE_MIN_FREE_SPACE", this::setMinFreeSpace);
		OptionUtils.applyEnvLong("LOOM_STORAGE_WARN_FREE_SPACE", this::setWarnFreeSpace);
		OptionUtils.applyEnvLong("LOOM_STORAGE_SPACE_CHECK_INTERVAL_MS", this::setSpaceCheckIntervalMs);
	}
}
