package io.metaloom.loom.rest.model.search;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One typeahead suggestion.
 */
public class SearchSuggestionResponse implements RestResponseModel<SearchSuggestionResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The suggested text.")
	private String text;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Kind of element the suggestion points at.")
	private String type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("UUID of that element.")
	private UUID uuid;

	@JsonPropertyDescription("Similarity of the suggestion to the typed prefix.")
	private double score;

	public String getText() {
		return text;
	}

	public SearchSuggestionResponse setText(String text) {
		this.text = text;
		return this;
	}

	public String getType() {
		return type;
	}

	public SearchSuggestionResponse setType(String type) {
		this.type = type;
		return this;
	}

	public UUID getUuid() {
		return uuid;
	}

	public SearchSuggestionResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public double getScore() {
		return score;
	}

	public SearchSuggestionResponse setScore(double score) {
		this.score = score;
		return this;
	}

	@Override
	public SearchSuggestionResponse self() {
		return this;
	}
}
