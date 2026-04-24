package io.metaloom.loom.rest.model.pool;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class AssetPoolResponse extends AbstractCreatorEditorRestResponse<AssetPoolResponse>
	implements AssetPoolModel<AssetPoolResponse> {

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

	@JsonPropertyDescription("Free space in bytes (filesystem pools only)")
	private Long freeSpace;

	@JsonPropertyDescription("Used space in bytes")
	private Long usedSpace;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public AssetPoolResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getFsPath() {
		return fsPath;
	}

	@Override
	public AssetPoolResponse setFsPath(String fsPath) {
		this.fsPath = fsPath;
		return this;
	}

	@Override
	public String getS3Bucket() {
		return s3Bucket;
	}

	@Override
	public AssetPoolResponse setS3Bucket(String s3Bucket) {
		this.s3Bucket = s3Bucket;
		return this;
	}

	@Override
	public String getS3Region() {
		return s3Region;
	}

	@Override
	public AssetPoolResponse setS3Region(String s3Region) {
		this.s3Region = s3Region;
		return this;
	}

	@Override
	public String getS3Endpoint() {
		return s3Endpoint;
	}

	@Override
	public AssetPoolResponse setS3Endpoint(String s3Endpoint) {
		this.s3Endpoint = s3Endpoint;
		return this;
	}

	@Override
	public Long getFreeSpace() {
		return freeSpace;
	}

	@Override
	public AssetPoolResponse setFreeSpace(Long freeSpace) {
		this.freeSpace = freeSpace;
		return this;
	}

	@Override
	public Long getUsedSpace() {
		return usedSpace;
	}

	@Override
	public AssetPoolResponse setUsedSpace(Long usedSpace) {
		this.usedSpace = usedSpace;
		return this;
	}

	@Override
	public AssetPoolResponse self() {
		return this;
	}
}
