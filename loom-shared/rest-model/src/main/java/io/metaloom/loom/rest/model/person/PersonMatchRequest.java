package io.metaloom.loom.rest.model.person;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Asks which known persons a set of vectors most resembles.
 *
 * <p>
 * The references are what reviewers confirmed - the centroids of confirmed clusters - plus the prints of the persons' own samples, all in the space
 * of {@link #getModel()}. The answer is a list of candidates to <em>suggest</em>; nothing here attributes anything to anybody.
 * </p>
 */
public class PersonMatchRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The cluster type to match in: speaker or face.")
	private String type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The model the probe vectors were produced by. Only references from the same model are compared.")
	private String model;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The probe vectors, e.g. one centroid per speaker found in an asset.")
	private List<Float[]> vectors = new ArrayList<>();

	@JsonProperty(required = false)
	@JsonPropertyDescription("Minimum cosine similarity for a candidate to be returned. Defaults to 0.5.")
	private Float threshold;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Candidates per probe, best first. Defaults to 3.")
	private Integer limit;

	@JsonProperty(required = false)
	@JsonPropertyDescription("How many samples still waiting for a print in this model to list. Defaults to 20; 0 lists none.")
	private Integer pendingLimit;

	public PersonMatchRequest() {
	}

	public String getType() {
		return type;
	}

	public PersonMatchRequest setType(String type) {
		this.type = type;
		return this;
	}

	public String getModel() {
		return model;
	}

	public PersonMatchRequest setModel(String model) {
		this.model = model;
		return this;
	}

	public List<Float[]> getVectors() {
		return vectors;
	}

	public PersonMatchRequest setVectors(List<Float[]> vectors) {
		this.vectors = vectors;
		return this;
	}

	public Float getThreshold() {
		return threshold;
	}

	public PersonMatchRequest setThreshold(Float threshold) {
		this.threshold = threshold;
		return this;
	}

	public Integer getLimit() {
		return limit;
	}

	public PersonMatchRequest setLimit(Integer limit) {
		this.limit = limit;
		return this;
	}

	public Integer getPendingLimit() {
		return pendingLimit;
	}

	public PersonMatchRequest setPendingLimit(Integer pendingLimit) {
		this.pendingLimit = pendingLimit;
		return this;
	}
}
