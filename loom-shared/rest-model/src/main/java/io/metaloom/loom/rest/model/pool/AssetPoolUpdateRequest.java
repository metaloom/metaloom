package io.metaloom.loom.rest.model.pool;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class AssetPoolUpdateRequest extends AbstractMetaModel<AssetPoolUpdateRequest>
	implements RestRequestModel, AssetPoolModel<AssetPoolUpdateRequest> {

	@JsonPropertyDescription("Unique human-readable name for the pool")
	private String name;

	@JsonPropertyDescription("Base filesystem path for the pool (only set for filesystem pools)")
	private String fsPath;

	@JsonPropertyDescription("S3 bucket name (only set for S3 pools)")
	private String s3Bucket;

	@JsonPropertyDescription("S3 region (only set for S3 pools)")
	private String s3Region;

	@JsonPropertyDescription("S3 endpoint URL for S3-compatible services (only set for S3 pools)")
	private String s3Endpoint;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public AssetPoolUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getFsPath() {
		return fsPath;
	}

	@Override
	public AssetPoolUpdateRequest setFsPath(String fsPath) {
		this.fsPath = fsPath;
		return this;
	}

	@Override
	public String getS3Bucket() {
		return s3Bucket;
	}

	@Override
	public AssetPoolUpdateRequest setS3Bucket(String s3Bucket) {
		this.s3Bucket = s3Bucket;
		return this;
	}

	@Override
	public String getS3Region() {
		return s3Region;
	}

	@Override
	public AssetPoolUpdateRequest setS3Region(String s3Region) {
		this.s3Region = s3Region;
		return this;
	}

	@Override
	public String getS3Endpoint() {
		return s3Endpoint;
	}

	@Override
	public AssetPoolUpdateRequest setS3Endpoint(String s3Endpoint) {
		this.s3Endpoint = s3Endpoint;
		return this;
	}

	@Override
	public AssetPoolUpdateRequest self() {
		return this;
	}
}
