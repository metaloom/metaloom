package io.metaloom.loom.rest.model.asset.location;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class AssetLocationResponse extends AbstractCreatorEditorRestResponse<AssetLocationResponse>
	implements AssetLocationModel<AssetLocationResponse> {

	private UUID libraryUuid;

	private UUID assetUuid;

	@JsonPropertyDescription("Reference to the storage pool in which the binary of this location is stored.")
	private UUID poolUuid;

	@JsonPropertyDescription("Mime type of the binary at this location.")
	private String mimeType;

	@JsonPropertyDescription("Current state of the location (e.g. whether the binary is present or missing).")
	private String state;

	@JsonPropertyDescription("License which applies to the binary at this location.")
	private String license;

	@JsonPropertyDescription("Uuid of the user which currently holds a lock on this location.")
	private UUID lockedByUuid;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetLocationFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	public AssetLocationResponse() {
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public AssetLocationResponse setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public AssetLocationResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public String getState() {
		return state;
	}

	public AssetLocationResponse setState(String state) {
		this.state = state;
		return this;
	}

	public String getLicense() {
		return license;
	}

	public AssetLocationResponse setLicense(String license) {
		this.license = license;
		return this;
	}

	public UUID getLockedByUuid() {
		return lockedByUuid;
	}

	public AssetLocationResponse setLockedByUuid(UUID lockedByUuid) {
		this.lockedByUuid = lockedByUuid;
		return this;
	}

	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	public AssetLocationResponse setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public AssetLocationResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AssetLocationFilesystemInfo getFilesystem() {
		return filesystem;
	}

	@Override
	public AssetLocationResponse setFilesystem(AssetLocationFilesystemInfo location) {
		this.filesystem = location;
		return this;
	}

	@Override
	public AssetS3Meta getS3() {
		return s3;
	}

	@Override
	public AssetLocationResponse setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	@Override
	public AssetLocationResponse self() {
		return this;
	}

}
