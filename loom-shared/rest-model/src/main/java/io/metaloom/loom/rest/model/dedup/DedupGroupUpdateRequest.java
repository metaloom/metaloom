package io.metaloom.loom.rest.model.dedup;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Confirm/deny a dedup review group. A reviewer sets {@code status} to {@code CONFIRMED} or {@code REJECTED} and optionally chooses the KEEP asset.
 */
public class DedupGroupUpdateRequest implements RestRequestModel {

	private String status;
	private String keepAssetUuid;

	public String getStatus() {
		return status;
	}

	public DedupGroupUpdateRequest setStatus(String status) {
		this.status = status;
		return this;
	}

	public String getKeepAssetUuid() {
		return keepAssetUuid;
	}

	public DedupGroupUpdateRequest setKeepAssetUuid(String keepAssetUuid) {
		this.keepAssetUuid = keepAssetUuid;
		return this;
	}
}
