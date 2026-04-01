package io.metaloom.loom.rest.model.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class LibraryCreateRequest implements RestRequestModel, LibraryModel<LibraryCreateRequest> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the library.")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public LibraryCreateRequest() {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public LibraryCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public LibraryCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public LibraryCreateRequest self() {
		return this;
	}

}
