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

	@EnvironmentVariable(name = "LOOM_STORAGE_UPLOAD_DIR", description = "Override the directory in which uploaded asset binaries are stored. Also accepted as LOOM_BINARY_DIR.")
	private String uploadDirectory = DEFAULT_UPLOAD_DIRECTORY;

	@EnvironmentVariable(name = "LOOM_STORAGE_MAX_UPLOAD_SIZE", description = "Largest accepted upload in bytes. -1 disables the cap.")
	private long maxUploadSize = DEFAULT_MAX_UPLOAD_SIZE;

	@EnvironmentVariable(name = "LOOM_STORAGE_MIN_FREE_SPACE", description = "Refuse uploads once the target filesystem would drop below this many free bytes. 0 disables the check. Not applicable to S3 pools.")
	private long minFreeSpace = DEFAULT_MIN_FREE_SPACE;

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

	@Override
	public void validate(OptionErrors errors) {
		errors.notBlank("uploadDirectory", uploadDirectory);
		if (maxUploadSize == 0 || maxUploadSize < -1) {
			errors.add("maxUploadSize", "The maximum upload size (LOOM_STORAGE_MAX_UPLOAD_SIZE) must be positive, or -1 for no limit.");
		}
		if (minFreeSpace < 0) {
			errors.add("minFreeSpace", "The minimum free space (LOOM_STORAGE_MIN_FREE_SPACE) must not be negative. Use 0 to disable the check.");
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
	}
}
