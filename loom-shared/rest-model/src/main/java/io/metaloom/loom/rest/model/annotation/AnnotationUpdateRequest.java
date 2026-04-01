package io.metaloom.loom.rest.model.annotation;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class AnnotationUpdateRequest extends AbstractMetaModel<AnnotationUpdateRequest>
	implements RestRequestModel, AnnotationModel<AnnotationUpdateRequest> {

	@JsonPropertyDescription("The type of the annotation.")
	private AnnotationType type;

	@JsonPropertyDescription("Title of the annotation")
	private String title;

	@JsonPropertyDescription("Spatial or temporal area the annotation references in the asset.")
	private AreaInfo area;

	@JsonPropertyDescription("Description of the annotation")
	private String description;

	private UUID assetUuid;

	@Override
	public AnnotationType getType() {
		return type;
	}

	@Override
	public AnnotationUpdateRequest setType(AnnotationType type) {
		this.type = type;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public AnnotationUpdateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public AnnotationUpdateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public AnnotationUpdateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AnnotationUpdateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AnnotationUpdateRequest self() {
		return this;
	}

}
