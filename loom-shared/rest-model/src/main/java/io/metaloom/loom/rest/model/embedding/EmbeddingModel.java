package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;

public interface EmbeddingModel<T extends EmbeddingModel<T>> extends MetaModel<T>, RestModel {

	AreaInfo getArea();

	T setArea(AreaInfo area);

	Float[] getVector();

	T setVector(Float[] vector);

	EmbeddingType getType();

	T setType(EmbeddingType type);

	UUID getAssetUuid();

	T setAssetUuid(UUID assetUuid);

	String getSource();

	T setSource(String source);

}
