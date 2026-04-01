package io.metaloom.loom.rest.model.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class LibraryResponse extends AbstractCreatorEditorRestResponse<LibraryResponse> implements LibraryModel<LibraryResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the library")
	private String name;

	public LibraryResponse() {
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
