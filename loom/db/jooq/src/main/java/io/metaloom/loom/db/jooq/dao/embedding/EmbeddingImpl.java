package io.metaloom.loom.db.jooq.dao.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.embedding.Embedding;

public class EmbeddingImpl extends AbstractEditableElement<Embedding> implements Embedding {

	private String source;

	private Float[] vector;

	private EmbeddingType type;

	private UUID assetUuid;

	public EmbeddingImpl() {
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public Embedding setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Embedding setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingType getType() {
		return type;
	}

	@Override
	public Embedding setType(EmbeddingType type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public Embedding setVector(Float[] vectorData) {
		this.vector = vectorData;
		return this;
	}

}
