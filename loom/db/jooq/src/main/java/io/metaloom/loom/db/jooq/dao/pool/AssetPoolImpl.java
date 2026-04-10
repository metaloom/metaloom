package io.metaloom.loom.db.jooq.dao.pool;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pool.AssetPool;

public class AssetPoolImpl extends AbstractEditableElement<AssetPool> implements AssetPool {

	private String name;

	private String fsPath;

	private String s3Bucket;

	private String s3Region;

	private String s3Endpoint;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public AssetPool setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getFsPath() {
		return fsPath;
	}

	@Override
	public AssetPool setFsPath(String fsPath) {
		this.fsPath = fsPath;
		return this;
	}

	@Override
	public String getS3Bucket() {
		return s3Bucket;
	}

	@Override
	public AssetPool setS3Bucket(String s3Bucket) {
		this.s3Bucket = s3Bucket;
		return this;
	}

	@Override
	public String getS3Region() {
		return s3Region;
	}

	@Override
	public AssetPool setS3Region(String s3Region) {
		this.s3Region = s3Region;
		return this;
	}

	@Override
	public String getS3Endpoint() {
		return s3Endpoint;
	}

	@Override
	public AssetPool setS3Endpoint(String s3Endpoint) {
		this.s3Endpoint = s3Endpoint;
		return this;
	}

	@Override
	public String toString() {
		return String.format("AssetPool [%s] - %s", getUuid(), getName());
	}

}
