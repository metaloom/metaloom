package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class PersonCreateRequest implements RestRequestModel, PersonModel<PersonCreateRequest> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The alias of the person.")
	private String alias;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The first name of the person.")
	private String firstname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The last name of the person.")
	private String lastname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public PersonCreateRequest() {
	}

	@Override
	public String getAlias() {
		return alias;
	}

	@Override
	public PersonCreateRequest setAlias(String alias) {
		this.alias = alias;
		return this;
	}

	@Override
	public String getFirstname() {
		return firstname;
	}

	@Override
	public PersonCreateRequest setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	@Override
	public String getLastname() {
		return lastname;
	}

	@Override
	public PersonCreateRequest setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PersonCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public PersonCreateRequest self() {
		return this;
	}
}
