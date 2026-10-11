package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Stores what a node made of one of a person's samples with its model: a reference vector, or the reason there is none.
 *
 * <p>
 * Written by the node that owns the model (the voiceprint node for voices), never by a person. Writing again for the same sample, type and model
 * replaces the earlier outcome.
 * </p>
 */
public class PersonPrintCreateRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The cluster type the print is comparable with, e.g. speaker or face.")
	private String type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The model that produced the vector - the same id the node writes on its clusters.")
	private String model;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The reference vector. Omit it and set error when the sample was unusable.")
	private Float[] vector;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Why the sample produced no vector. Recorded so the sample is not tried again in this model.")
	private String error;

	public PersonPrintCreateRequest() {
	}

	public String getType() {
		return type;
	}

	public PersonPrintCreateRequest setType(String type) {
		this.type = type;
		return this;
	}

	public String getModel() {
		return model;
	}

	public PersonPrintCreateRequest setModel(String model) {
		this.model = model;
		return this;
	}

	public Float[] getVector() {
		return vector;
	}

	public PersonPrintCreateRequest setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	public String getError() {
		return error;
	}

	public PersonPrintCreateRequest setError(String error) {
		this.error = error;
		return this;
	}
}
