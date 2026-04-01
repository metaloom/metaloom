package io.metaloom.loom.rest.model.role;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class RoleUpdateRequest implements MetaModel<RoleUpdateRequest>, RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The new name of the role.")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	@JsonProperty(required = false)
	@JsonPropertyDescription("A list of permissions that are granted by the role.")
	private List<RolePermission> permissions;

	public RoleUpdateRequest() {
	}

	public String getName() {
		return name;
	}

	public RoleUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public JsonObject getMeta() {
		return meta;
	}

	public RoleUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public List<RolePermission> getPermissions() {
		return permissions;
	}

	public RoleUpdateRequest setPermissions(List<RolePermission> permissions) {
		this.permissions = permissions;
		return this;
	}

	@Override
	public RoleUpdateRequest self() {
		return this;
	}

}
