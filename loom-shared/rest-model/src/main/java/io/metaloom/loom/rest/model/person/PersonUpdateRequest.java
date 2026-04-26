package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class PersonUpdateRequest implements RestRequestModel, MetaModel<PersonUpdateRequest> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The alias of the person.")
	private String alias;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The first name of the person.")
	private String firstname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The last name of the person.")
	private String lastname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("UUID of the primary gallery image.")
	private String primaryImageUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public PersonUpdateRequest() {
	}

	public String getAlias() {
		return alias;
	}

	public PersonUpdateRequest setAlias(String alias) {
		this.alias = alias;
		return this;
	}

	public String getFirstname() {
		return firstname;
	}

	public PersonUpdateRequest setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	public String getLastname() {
		return lastname;
	}

	public PersonUpdateRequest setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	public String getPrimaryImageUuid() {
		return primaryImageUuid;
	}

	public PersonUpdateRequest setPrimaryImageUuid(String primaryImageUuid) {
		this.primaryImageUuid = primaryImageUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PersonUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public PersonUpdateRequest self() {
		return this;
	}
}
