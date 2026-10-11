package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The outcome of embedding one of a person's samples with one model. The vector itself is never returned: a voiceprint is biometric and nothing
 * outside matching needs it.
 */
public class PersonPrintResponse implements RestResponseModel<PersonPrintResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Identity of the print.")
	private String uuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The sample the print was computed from.")
	private String sampleUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The cluster type the print is comparable with, e.g. speaker.")
	private String type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The model that produced the print.")
	private String model;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Length of the vector; null when the sample was unusable.")
	private Integer dimensions;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether the sample produced a vector. An unusable sample is not retried in this model.")
	private boolean usable;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Why the sample produced no vector, e.g. no speech found.")
	private String error;

	@JsonProperty(required = false)
	@JsonPropertyDescription("When the print was last computed (ISO-8601).")
	private String edited;

	public PersonPrintResponse() {
	}

	public String getUuid() {
		return uuid;
	}

	public PersonPrintResponse setUuid(String uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getSampleUuid() {
		return sampleUuid;
	}

	public PersonPrintResponse setSampleUuid(String sampleUuid) {
		this.sampleUuid = sampleUuid;
		return this;
	}

	public String getType() {
		return type;
	}

	public PersonPrintResponse setType(String type) {
		this.type = type;
		return this;
	}

	public String getModel() {
		return model;
	}

	public PersonPrintResponse setModel(String model) {
		this.model = model;
		return this;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public PersonPrintResponse setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	public boolean isUsable() {
		return usable;
	}

	public PersonPrintResponse setUsable(boolean usable) {
		this.usable = usable;
		return this;
	}

	public String getError() {
		return error;
	}

	public PersonPrintResponse setError(String error) {
		this.error = error;
		return this;
	}

	public String getEdited() {
		return edited;
	}

	public PersonPrintResponse setEdited(String edited) {
		this.edited = edited;
		return this;
	}

	@Override
	public PersonPrintResponse self() {
		return this;
	}
}
