package io.metaloom.loom.db.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CUDElement;

public interface Embedding extends CUDElement<Embedding> {

	UUID getAssetUuid();

	Embedding setAssetUuid(UUID assetUuid);

	Float[] getVector();

	Embedding setVector(Float[] vectorData);

	String getSource();

	Embedding setSource(String source);

	EmbeddingType getType();

	Embedding setType(EmbeddingType type);

}
