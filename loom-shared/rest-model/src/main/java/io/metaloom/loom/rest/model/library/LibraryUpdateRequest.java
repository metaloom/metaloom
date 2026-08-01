package io.metaloom.loom.rest.model.library;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class LibraryUpdateRequest implements RestRequestModel, MetaModel<LibraryUpdateRequest> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The name of the library.")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Storage pool for binaries uploaded into this library. Only affects binaries uploaded after the change; bytes already stored are not migrated.")
	private UUID poolUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public LibraryUpdateRequest() {
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public LibraryUpdateRequest setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getName() {
		return name;
	}

	public LibraryUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public LibraryUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public LibraryUpdateRequest self() {
		return this;
	}
}
