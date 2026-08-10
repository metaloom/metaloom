package io.metaloom.loom.rest.model.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The configured limits the watermarks are graded against, so a caller can render "8 GB free of the 5 GB warning mark" without being told them
 * separately.
 */
public class StorageThresholdsModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Free bytes below which uploads are refused with 507 (LOOM_STORAGE_MIN_FREE_SPACE). 0 disables the check.")
	private long minFreeSpaceBytes;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Free bytes below which a backend is reported as degraded (LOOM_STORAGE_WARN_FREE_SPACE). Nothing is refused. 0 disables the warning level.")
	private long warnFreeSpaceBytes;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Largest accepted upload in bytes (LOOM_STORAGE_MAX_UPLOAD_SIZE). -1 means no cap.")
	private long maxUploadSizeBytes;

	public StorageThresholdsModel() {
	}

	public long getMinFreeSpaceBytes() {
		return minFreeSpaceBytes;
	}

	public StorageThresholdsModel setMinFreeSpaceBytes(long minFreeSpaceBytes) {
		this.minFreeSpaceBytes = minFreeSpaceBytes;
		return this;
	}

	public long getWarnFreeSpaceBytes() {
		return warnFreeSpaceBytes;
	}

	public StorageThresholdsModel setWarnFreeSpaceBytes(long warnFreeSpaceBytes) {
		this.warnFreeSpaceBytes = warnFreeSpaceBytes;
		return this;
	}

	public long getMaxUploadSizeBytes() {
		return maxUploadSizeBytes;
	}

	public StorageThresholdsModel setMaxUploadSizeBytes(long maxUploadSizeBytes) {
		this.maxUploadSizeBytes = maxUploadSizeBytes;
		return this;
	}
}
