package io.metaloom.loom.rest.model.asset.location.social;

public class Rating {

	private int stars;

	private int upVotes;

	private int downVotes;

	public int getStars() {
		return stars;
	}

	public Rating setStars(int stars) {
		this.stars = stars;
		return this;
	}

	public int getDownVotes() {
		return downVotes;
	}

	public Rating setDownVotes(int downVotes) {
		this.downVotes = downVotes;
		return this;
	}

	public int getUpVotes() {
		return upVotes;
	}

	public Rating setUpVotes(int upVotes) {
		this.upVotes = upVotes;
		return this;
	}

}
