package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;

/**
 * Move one placement of a tag to a different region of the asset.
 *
 * <p>
 * Only the region. A placement is <em>where</em> a tag sits, and its name, its collection and its provenance belong to the tag and to the act of
 * attaching it - changing those through this route would silently rewrite history a node wrote. Renaming the tag itself is
 * <code>PUT /tags/:uuid</code>; putting the same tag somewhere else as well is another <code>POST /assets/:uuid/tags</code>.
 * </p>
 *
 * <p>
 * The area replaces the stored one field by field: a field left out is left alone, which is what lets a caller drag a time range's end without
 * having to restate its start. Sending no area at all is a no-op rather than an error - the same request twice must mean the same thing.
 * </p>
 */
public class TagPlacementUpdateRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The region the placement moves to. Fields left unset keep their stored value.")
	private AreaInfo area;

	public TagPlacementUpdateRequest() {
	}

	public AreaInfo getArea() {
		return area;
	}

	public TagPlacementUpdateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}
}
