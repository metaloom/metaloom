package io.metaloom.loom.rest.model.asset.location;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class AssetLocationResponse extends AbstractCreatorEditorRestResponse<AssetLocationResponse>
	implements AssetLocationModel<AssetLocationResponse> {

	private UUID libraryUuid;

	private UUID assetUuid;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetLocationFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	public AssetLocationResponse() {
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
