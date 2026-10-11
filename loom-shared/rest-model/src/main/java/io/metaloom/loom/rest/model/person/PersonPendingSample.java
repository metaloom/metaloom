package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestModel;

/**
 * A person sample that has no print in the requested model yet - work the caller can do with the model it holds.
 */
public class PersonPendingSample implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The sample's owner.")
	private String personUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The sample.")
	private String sampleUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("What the bytes are.")
	private String mimeType;

	@JsonProperty(required = true)
	@JsonPropertyDescription("URL the sample bytes can be loaded from.")
	private String url;

	public PersonPendingSample() {
	}

	public String getPersonUuid() {
		return personUuid;
	}

	public PersonPendingSample setPersonUuid(String personUuid) {
		this.personUuid = personUuid;
		return this;
	}

	public String getSampleUuid() {
		return sampleUuid;
	}

	public PersonPendingSample setSampleUuid(String sampleUuid) {
		this.sampleUuid = sampleUuid;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public PersonPendingSample setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public PersonPendingSample setUrl(String url) {
		this.url = url;
		return this;
	}
}
