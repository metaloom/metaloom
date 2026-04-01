package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class EmbeddingResponse extends AbstractCreatorEditorRestResponse<EmbeddingResponse> implements EmbeddingModel<EmbeddingResponse> {

	private String source;

	private EmbeddingType type;

	private Float[] vector;

	private AreaInfo area;

	private UUID assetUuid;

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public EmbeddingResponse setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public EmbeddingType getType() {
		return type;
	}

	@Override
	public EmbeddingResponse setType(EmbeddingType type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingResponse setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingResponse setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public EmbeddingResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingResponse self() {
		return this;
	}

}
