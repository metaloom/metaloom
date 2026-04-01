package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.vertx.core.json.JsonObject;

public class EmbeddingUpdateRequest implements EmbeddingModel<EmbeddingUpdateRequest>, RestRequestModel {

	@JsonPropertyDescription("The meta properties for the embedding")
	private String source;

	@JsonPropertyDescription("The meta properties for the embedding")
	private JsonObject meta;

	@JsonPropertyDescription("The area information for the embedding (e.g. )")
	private AreaInfo area;

	private EmbeddingType type;

	private Float[] vector;

	private UUID assetUuid;

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public EmbeddingUpdateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public EmbeddingUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingUpdateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public EmbeddingType getType() {
		return type;
	}

	@Override
	public EmbeddingUpdateRequest setType(EmbeddingType type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingUpdateRequest setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public EmbeddingUpdateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingUpdateRequest self() {
		return this;
	}
}
