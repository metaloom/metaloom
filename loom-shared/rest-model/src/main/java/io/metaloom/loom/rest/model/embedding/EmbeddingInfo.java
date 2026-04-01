package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class EmbeddingInfo extends AbstractMetaModel<EmbeddingInfo> implements EmbeddingModel<EmbeddingInfo> {

	private UUID uuid;

	private String source;

	private EmbeddingType type;

	private Float[] vector;

	private AreaInfo area;

	private UUID assetUuid;

	public UUID getUuid() {
		return uuid;
	}

	public EmbeddingInfo setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public EmbeddingInfo setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public EmbeddingType getType() {
		return type;
	}

	@Override
	public EmbeddingInfo setType(EmbeddingType type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingInfo setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingInfo setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public EmbeddingInfo setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingInfo self() {
		return this;
	}

}
