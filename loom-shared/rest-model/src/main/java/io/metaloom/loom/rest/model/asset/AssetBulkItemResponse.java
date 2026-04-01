package io.metaloom.loom.rest.model.asset;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Individual result item within a bulk asset response. Reports success/failure per asset.
 */
public class AssetBulkItemResponse {

	@JsonPropertyDescription("Index of the item in the original request.")
	private int index;

	@JsonPropertyDescription("UUID of the created or existing asset (null on failure).")
	private UUID uuid;

	@JsonPropertyDescription("SHA-512 hash used in the request.")
	private String sha512;

	@JsonPropertyDescription("Status: CREATED, UPDATED, or FAILED.")
	private BulkItemStatus status;

	@JsonPropertyDescription("Error message (only present on failure).")
	private String error;

	public AssetBulkItemResponse() {
	}

	public int getIndex() {
		return index;
	}

	public AssetBulkItemResponse setIndex(int index) {
		this.index = index;
		return this;
	}

	public UUID getUuid() {
		return uuid;
	}

	public AssetBulkItemResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getSha512() {
		return sha512;
	}

	public AssetBulkItemResponse setSha512(String sha512) {
		this.sha512 = sha512;
		return this;
	}

	public BulkItemStatus getStatus() {
		return status;
	}

	public AssetBulkItemResponse setStatus(BulkItemStatus status) {
		this.status = status;
		return this;
	}

	public String getError() {
		return error;
	}

	public AssetBulkItemResponse setError(String error) {
		this.error = error;
		return this;
	}

	public enum BulkItemStatus {
		CREATED,
		UPDATED,
		FAILED
	}

}
