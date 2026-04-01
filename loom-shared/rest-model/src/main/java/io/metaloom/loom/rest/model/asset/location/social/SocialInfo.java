package io.metaloom.loom.rest.model.asset.location.social;

import java.util.HashMap;
import java.util.Map;

import io.metaloom.loom.api.reaction.ReactionType;

public class SocialInfo {

	private Rating rating = new Rating();

	private Map<ReactionType, Long> reactions = new HashMap<>();

	public Rating getRating() {
		return rating;
	}

	public SocialInfo setRating(Rating rating) {
		this.rating = rating;
		return this;
	}

	public Map<ReactionType, Long> getReactions() {
		return reactions;
	}

	public void setReactions(Map<ReactionType, Long> reactions) {
		this.reactions = reactions;
	}
}
