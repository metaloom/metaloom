package io.metaloom.loom.rest.model.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class GroupUpdateRequest implements RestRequestModel, GroupModel<GroupUpdateRequest> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Name of the group")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public GroupUpdateRequest() {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public GroupUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public GroupUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public GroupUpdateRequest self() {
		return this;
	}
}
