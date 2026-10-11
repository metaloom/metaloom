package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestModel;

/**
 * One person a probe resembles, and how closely.
 */
public class PersonMatchCandidate implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The person.")
	private String personUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The name the person is shown by: alias, else first and last name.")
	private String name;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Cosine similarity of the probe to the person's reference (the normalised mean of their reference vectors).")
	private float score;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many reference vectors the person's reference was built from.")
	private int references;

	public PersonMatchCandidate() {
	}

	public String getPersonUuid() {
		return personUuid;
	}

	public PersonMatchCandidate setPersonUuid(String personUuid) {
		this.personUuid = personUuid;
		return this;
	}

	public String getName() {
		return name;
	}

	public PersonMatchCandidate setName(String name) {
		this.name = name;
		return this;
	}

	public float getScore() {
		return score;
	}

	public PersonMatchCandidate setScore(float score) {
		this.score = score;
		return this;
	}

	public int getReferences() {
		return references;
	}

	public PersonMatchCandidate setReferences(int references) {
		this.references = references;
		return this;
	}
}
