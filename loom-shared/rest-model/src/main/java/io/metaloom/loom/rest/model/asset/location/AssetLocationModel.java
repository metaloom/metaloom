package io.metaloom.loom.rest.model.asset.location;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface AssetLocationModel<T extends AssetLocationModel<T>> extends MetaModel<T>, RestModel {

	AssetLocationFilesystemInfo getFilesystem();

	T setFilesystem(AssetLocationFilesystemInfo filesystem);

	AssetS3Meta getS3();

	T setS3(AssetS3Meta s3);

}
