package io.metaloom.loom.rest.model.person;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestModel;

/**
 * The candidates for one probe vector.
 */
public class PersonMatchResult implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Position of the probe in the request's vectors.")
	private int index;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Candidates at or above the threshold, best first. Empty when nobody resembles the probe closely enough.")
	private List<PersonMatchCandidate> candidates = new ArrayList<>();

	public PersonMatchResult() {
	}

	public int getIndex() {
		return index;
	}

	public PersonMatchResult setIndex(int index) {
		this.index = index;
		return this;
	}

	public List<PersonMatchCandidate> getCandidates() {
		return candidates;
	}

	public PersonMatchResult setCandidates(List<PersonMatchCandidate> candidates) {
		this.candidates = candidates;
		return this;
	}
}
