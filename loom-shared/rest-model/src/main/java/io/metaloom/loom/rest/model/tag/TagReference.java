package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractNamedReference;

public class TagReference extends AbstractNamedReference<TagReference> {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Spatial or temporal region of the asset that the tag references. Only set for region tags.")
	private AreaInfo area;

	public AreaInfo getArea() {
		return area;
	}

	public TagReference setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public TagReference self() {
		return this;
	}

}
