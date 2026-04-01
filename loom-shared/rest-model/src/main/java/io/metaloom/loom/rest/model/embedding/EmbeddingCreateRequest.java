package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class EmbeddingCreateRequest extends AbstractMetaModel<EmbeddingCreateRequest>
	implements EmbeddingModel<EmbeddingCreateRequest>, RestRequestModel {

	private String source;

	private AreaInfo area;

	private EmbeddingType type;

	private Float[] vector;

	private UUID assetUuid;

	public String getSource() {
		return source;
	}

	public EmbeddingCreateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingCreateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	public EmbeddingType getType() {
		return type;
	}

	public EmbeddingCreateRequest setType(EmbeddingType type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingCreateRequest setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public EmbeddingCreateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingCreateRequest self() {
		return this;
	}

}
