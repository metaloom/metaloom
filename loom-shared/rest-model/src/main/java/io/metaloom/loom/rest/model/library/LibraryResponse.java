package io.metaloom.loom.rest.model.library;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class LibraryResponse extends AbstractCreatorEditorRestResponse<LibraryResponse> implements LibraryModel<LibraryResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the library")
	private String name;

	@JsonPropertyDescription("The storage pool binaries uploaded into this library are written to. Absent when the library uses the server's local upload directory.")
	private UUID poolUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Which backend this library stores binaries in: 'filesystem' or 's3'. Derived from the pool, so it is never null even when no pool is set.")
	private String storageType;

	public LibraryResponse() {
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public LibraryResponse setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getStorageType() {
		return storageType;
	}

	public LibraryResponse setStorageType(String storageType) {
		this.storageType = storageType;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public LibraryResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public LibraryResponse self() {
		return this;
	}
}
