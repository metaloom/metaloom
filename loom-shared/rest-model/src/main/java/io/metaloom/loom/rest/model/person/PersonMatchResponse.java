package io.metaloom.loom.rest.model.person;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The answer to a {@link PersonMatchRequest}.
 */
public class PersonMatchResponse implements RestResponseModel<PersonMatchResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("One entry per probe vector, in request order.")
	private List<PersonMatchResult> results = new ArrayList<>();

	@JsonProperty(required = true)
	@JsonPropertyDescription("Samples of persons that have no print in this model yet. A node holding the model embeds them and stores the prints, so the next match can use them.")
	private List<PersonPendingSample> pendingSamples = new ArrayList<>();

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many persons had at least one reference in this space.")
	private int personCount;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many reference vectors were compared against.")
	private int referenceCount;

	public PersonMatchResponse() {
	}

	public List<PersonMatchResult> getResults() {
		return results;
	}

	public PersonMatchResponse setResults(List<PersonMatchResult> results) {
		this.results = results;
		return this;
	}

	public List<PersonPendingSample> getPendingSamples() {
		return pendingSamples;
	}

	public PersonMatchResponse setPendingSamples(List<PersonPendingSample> pendingSamples) {
		this.pendingSamples = pendingSamples;
		return this;
	}

	public int getPersonCount() {
		return personCount;
	}

	public PersonMatchResponse setPersonCount(int personCount) {
		this.personCount = personCount;
		return this;
	}

	public int getReferenceCount() {
		return referenceCount;
	}

	public PersonMatchResponse setReferenceCount(int referenceCount) {
		this.referenceCount = referenceCount;
		return this;
	}

	@Override
	public PersonMatchResponse self() {
		return this;
	}
}
