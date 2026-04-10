package io.metaloom.loom.rest.model.asset.binary;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class AssetBinaryResponse extends AbstractCreatorEditorRestResponse<AssetBinaryResponse>
	implements AssetBinaryModel<AssetBinaryResponse> {

	private UUID libraryUuid;

	private UUID assetUuid;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetBinaryFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	public AssetBinaryResponse() {
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
