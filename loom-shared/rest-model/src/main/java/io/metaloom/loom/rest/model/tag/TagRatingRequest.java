package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

public class TagRatingRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Rating which the current user assigns to the tag.")
	private Integer rating;

	public TagRatingRequest() {
	}

	public Integer getRating() {
		return rating;
	}

	public TagRatingRequest setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public TagRatingRequest self() {
		return this;
	}

}
