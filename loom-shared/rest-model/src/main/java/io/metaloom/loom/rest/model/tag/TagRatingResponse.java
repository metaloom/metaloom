package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

public class TagRatingResponse implements RestResponseModel<TagRatingResponse> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Rating which the current user assigned to the tag. May be null when the user has not yet rated the tag.")
	private Integer rating;

	public TagRatingResponse() {
	}

	public Integer getRating() {
		return rating;
	}

	public TagRatingResponse setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public TagRatingResponse self() {
		return this;
	}

}
