package io.metaloom.loom.rest.model.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class GroupCreateRequest implements RestRequestModel, GroupModel<GroupCreateRequest> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the group")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public GroupCreateRequest() {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public GroupCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public GroupCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public GroupCreateRequest self() {
		return this;
	}
}
