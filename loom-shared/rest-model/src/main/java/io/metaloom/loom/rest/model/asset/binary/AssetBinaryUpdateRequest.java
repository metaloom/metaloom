package io.metaloom.loom.rest.model.asset.binary;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class AssetBinaryUpdateRequest implements RestRequestModel, AssetBinaryModel<AssetBinaryUpdateRequest> {

	@JsonPropertyDescription("Library the binary belongs to. Set this to move the binary into another library; leave it unset to keep the current one.")
	private UUID libraryUuid;

	@JsonPropertyDescription("Storage pool holding the bytes. Set this to record that the bytes now live in another pool. Defaults to the pool of the given library when only libraryUuid is set, and is otherwise left unchanged. Requires the READ_ASSET_POOL permission.")
	private UUID poolUuid;

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetBinaryFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	public AssetBinaryUpdateRequest setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public AssetBinaryUpdateRequest setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public AssetBinaryUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public AssetBinaryFilesystemInfo getFilesystem() {
		return filesystem;
	}

	@Override
	public AssetBinaryUpdateRequest setFilesystem(AssetBinaryFilesystemInfo filesystem) {
		this.filesystem = filesystem;
		return this;
	}

	@Override
	public AssetS3Meta getS3() {
		return s3;
	}

	@Override
	public AssetBinaryUpdateRequest setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	@Override
	public AssetBinaryUpdateRequest self() {
		return this;
	}

}
