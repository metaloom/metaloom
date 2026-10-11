package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Designates which of a person's voice samples is their hero sample - the voice counterpart of {@link PersonAvatarRequest}.
 */
public class PersonHeroVoiceRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The voice sample to represent the person with. Blank or absent clears it.")
	private String sampleUuid;

	public PersonHeroVoiceRequest() {
	}

	public String getSampleUuid() {
		return sampleUuid;
	}

	public PersonHeroVoiceRequest setSampleUuid(String sampleUuid) {
		this.sampleUuid = sampleUuid;
		return this;
	}
}
