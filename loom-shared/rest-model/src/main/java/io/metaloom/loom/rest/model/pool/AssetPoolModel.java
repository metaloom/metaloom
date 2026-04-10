package io.metaloom.loom.rest.model.pool;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface AssetPoolModel<T extends AssetPoolModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getFsPath();

	T setFsPath(String fsPath);

	String getS3Bucket();

	T setS3Bucket(String s3Bucket);

	String getS3Region();

	T setS3Region(String s3Region);

	String getS3Endpoint();

	T setS3Endpoint(String s3Endpoint);

}
