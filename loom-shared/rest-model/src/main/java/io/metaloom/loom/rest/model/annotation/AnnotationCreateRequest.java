package io.metaloom.loom.rest.model.annotation;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class AnnotationCreateRequest implements RestRequestModel, MetaModel<AnnotationCreateRequest>, AnnotationModel<AnnotationCreateRequest> {

	@JsonPropertyDescription("The type of the annotation.")
	private AnnotationType type;

	@JsonPropertyDescription("Title of the annotation")
	private String title;

	@JsonPropertyDescription("Spatial or temporal area the annotation references in the asset.")
	private AreaInfo area;

	@JsonPropertyDescription("Description of the annotation")
	private String description;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	private UUID assetUuid;

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public AnnotationCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public AnnotationType getType() {
		return type;
	}

	@Override
	public AnnotationCreateRequest setType(AnnotationType type) {
		this.type = type;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public AnnotationCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public AnnotationCreateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public AnnotationCreateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AnnotationCreateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AnnotationCreateRequest self() {
		return this;
	}

}
