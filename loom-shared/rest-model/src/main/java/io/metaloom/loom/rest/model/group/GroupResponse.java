package io.metaloom.loom.rest.model.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class GroupResponse extends AbstractCreatorEditorRestResponse<GroupResponse> implements GroupModel<GroupResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the group")
	private String name;

	public GroupResponse() {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public GroupResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public GroupResponse self() {
		return this;
	}
}
