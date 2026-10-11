package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Cuts a stretch of a speaker's voice out of the asset it was heard in, into a person's own voice samples.
 *
 * <p>
 * A copy, like importing a face crop into a person's images: the sample belongs to the person and outlives the asset. Importing is a deliberate act;
 * confirming a speaker cluster does not do it.
 * </p>
 */
public class PersonVoiceSampleImportRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The speaker cluster to take the voice from.")
	private String clusterUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Start of the stretch to cut, in milliseconds. Omit both times to take the speaker's longest turn, at most 30 seconds of it.")
	private Long timeFrom;

	@JsonProperty(required = false)
	@JsonPropertyDescription("End of the stretch to cut, in milliseconds. At most 120 seconds after timeFrom.")
	private Long timeTo;

	public PersonVoiceSampleImportRequest() {
	}

	public String getClusterUuid() {
		return clusterUuid;
	}

	public PersonVoiceSampleImportRequest setClusterUuid(String clusterUuid) {
		this.clusterUuid = clusterUuid;
		return this;
	}

	public Long getTimeFrom() {
		return timeFrom;
	}

	public PersonVoiceSampleImportRequest setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Long getTimeTo() {
		return timeTo;
	}

	public PersonVoiceSampleImportRequest setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}
}
