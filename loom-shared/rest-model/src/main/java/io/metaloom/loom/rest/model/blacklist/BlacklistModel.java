package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface BlacklistModel<T extends BlacklistModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getAssetUuid();

	T setAssetUuid(String assetUuid);

}
