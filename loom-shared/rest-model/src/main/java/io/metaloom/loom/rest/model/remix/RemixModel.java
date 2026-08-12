package io.metaloom.loom.rest.model.remix;

import java.util.UUID;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface RemixModel<T extends RemixModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getDescription();

	T setDescription(String description);

	UUID getSourceAssetUuid();

	T setSourceAssetUuid(UUID sourceAssetUuid);

}
