package io.metaloom.loom.db.model.pool;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface AssetPool extends CUDElement<AssetPool>, MetaElement<AssetPool> {

	String getName();

	AssetPool setName(String name);

	// Filesystem pool

	String getFsPath();

	AssetPool setFsPath(String fsPath);

	// S3 pool

	String getS3Bucket();

	AssetPool setS3Bucket(String s3Bucket);

	String getS3Region();

	AssetPool setS3Region(String s3Region);

	String getS3Endpoint();

	AssetPool setS3Endpoint(String s3Endpoint);

}
