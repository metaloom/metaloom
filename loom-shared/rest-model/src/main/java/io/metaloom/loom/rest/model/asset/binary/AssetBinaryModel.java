package io.metaloom.loom.rest.model.asset.binary;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface AssetBinaryModel<T extends AssetBinaryModel<T>> extends MetaModel<T>, RestModel {

	AssetBinaryFilesystemInfo getFilesystem();

	T setFilesystem(AssetBinaryFilesystemInfo filesystem);

	AssetS3Meta getS3();

	T setS3(AssetS3Meta s3);

}
