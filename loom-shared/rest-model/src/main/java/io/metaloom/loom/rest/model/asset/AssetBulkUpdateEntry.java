package io.metaloom.loom.rest.model.asset;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.asset.info.HashInfo;

/**
 * Individual entry within a bulk update request. The SHA-512 hash identifies the target asset.
 */
public class AssetBulkUpdateEntry {

	@JsonPropertyDescription("The hashes used to identify and update the asset. SHA-512 is required as key.")
	private HashInfo hashes;

	@JsonPropertyDescription("The update payload for this asset.")
	private AssetUpdateRequest update;

	public AssetBulkUpdateEntry() {
	}

	public HashInfo getHashes() {
		return hashes;
	}

	public AssetBulkUpdateEntry setHashes(HashInfo hashes) {
		this.hashes = hashes;
		return this;
	}

	public AssetUpdateRequest getUpdate() {
		return update;
	}

	public AssetBulkUpdateEntry setUpdate(AssetUpdateRequest update) {
		this.update = update;
		return this;
	}

}
