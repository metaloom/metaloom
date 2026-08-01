package io.metaloom.loom.rest.model.asset.binary;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class AssetBinaryResponse extends AbstractCreatorEditorRestResponse<AssetBinaryResponse>
	implements AssetBinaryModel<AssetBinaryResponse> {

	private UUID libraryUuid;

	private UUID assetUuid;

	@JsonPropertyDescription("The storage pool holding these bytes. Absent when the binary lives in the server's local upload directory.")
	private UUID poolUuid;

	@JsonPropertyDescription("Which backend holds the bytes: 'filesystem' or 's3'. Derived from the pool; never null.")
	private String storageType;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem. Only set for filesystem-backed binaries.")
	private AssetBinaryFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. Only set for S3-backed binaries.")
	private AssetS3Meta s3;

	public AssetBinaryResponse() {
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public AssetBinaryResponse setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getStorageType() {
		return storageType;
	}

	public AssetBinaryResponse setStorageType(String storageType) {
		this.storageType = storageType;
		return this;
	}

	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	public AssetBinaryResponse setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public AssetBinaryResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AssetBinaryFilesystemInfo getFilesystem() {
		return filesystem;
	}

	@Override
	public AssetBinaryResponse setFilesystem(AssetBinaryFilesystemInfo location) {
		this.filesystem = location;
		return this;
	}

	@Override
	public AssetS3Meta getS3() {
		return s3;
	}

	@Override
	public AssetBinaryResponse setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	@Override
	public AssetBinaryResponse self() {
		return this;
	}

}
