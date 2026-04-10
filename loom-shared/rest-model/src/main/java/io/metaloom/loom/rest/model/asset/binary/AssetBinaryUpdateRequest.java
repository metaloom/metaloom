package io.metaloom.loom.rest.model.asset.binary;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class AssetBinaryUpdateRequest implements RestRequestModel, AssetBinaryModel<AssetBinaryUpdateRequest> {

	@JsonPropertyDescription("Information about the location of the asset in the filesystem.")
	private AssetBinaryFilesystemInfo filesystem;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	private AssetS3Meta s3;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

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
