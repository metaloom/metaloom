package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class PersonResponse extends AbstractCreatorEditorRestResponse<PersonResponse> implements PersonModel<PersonResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The alias of the person")
	private String alias;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The first name of the person")
	private String firstname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The last name of the person")
	private String lastname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("UUID of the primary gallery image")
	private String primaryImageUuid;

	public PersonResponse() {
	}

	@Override
	public String getAlias() {
		return alias;
	}

	@Override
	public PersonResponse setAlias(String alias) {
		this.alias = alias;
		return this;
	}

	@Override
	public String getFirstname() {
		return firstname;
	}

	@Override
	public PersonResponse setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	@Override
	public String getLastname() {
		return lastname;
	}

	@Override
	public PersonResponse setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	@Override
	public String getPrimaryImageUuid() {
		return primaryImageUuid;
	}

	@Override
	public PersonResponse setPrimaryImageUuid(String primaryImageUuid) {
		this.primaryImageUuid = primaryImageUuid;
		return this;
	}

	@Override
	public PersonResponse self() {
		return this;
	}
}
