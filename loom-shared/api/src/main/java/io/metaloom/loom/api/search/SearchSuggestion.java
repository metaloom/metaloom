package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One typeahead suggestion.
 */
public class SearchSuggestion {

	private String text;

	private SearchEntityType type;

	private UUID uuid;

	private double score;

	public SearchSuggestion() {
	}

	public SearchSuggestion(String text, SearchEntityType type, UUID uuid, double score) {
		this.text = text;
		this.type = type;
		this.uuid = uuid;
		this.score = score;
	}

	public String getText() {
		return text;
	}

	public SearchSuggestion setText(String text) {
		this.text = text;
		return this;
	}

	public SearchEntityType getType() {
		return type;
	}

	public SearchSuggestion setType(SearchEntityType type) {
		this.type = type;
		return this;
	}

	public UUID getUuid() {
		return uuid;
	}

	public SearchSuggestion setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public double getScore() {
		return score;
	}

	public SearchSuggestion setScore(double score) {
		this.score = score;
		return this;
	}

	@Override
	public String toString() {
		return text + " (" + type + ")";
	}
}
