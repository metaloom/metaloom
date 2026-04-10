package io.metaloom.loom.rest.model.asset.binary;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class AssetBinaryCreateRequest extends AbstractMetaModel<AssetBinaryCreateRequest>
	implements RestRequestModel, AssetBinaryModel<AssetBinaryCreateRequest> {

	private UUID assetUuid;

	private UUID libraryUuid;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetBinaryFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public AssetBinaryCreateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	public AssetBinaryCreateRequest setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	@Override
	public AssetBinaryFilesystemInfo getFilesystem() {
		return filesystem;
	}

	@Override
	public AssetBinaryCreateRequest setFilesystem(AssetBinaryFilesystemInfo filesystem) {
		this.filesystem = filesystem;
		return this;
	}

	@Override
	public AssetS3Meta getS3() {
		return s3;
	}

	@Override
	public AssetBinaryCreateRequest setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	@Override
	public AssetBinaryCreateRequest self() {
		return this;
	}
}
