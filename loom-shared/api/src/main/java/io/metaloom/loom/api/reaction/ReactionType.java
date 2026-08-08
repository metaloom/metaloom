package io.metaloom.loom.api.reaction;

public enum ReactionType {

	// TODO lookup better source or convert https://gist.github.com/rxaviers/7360908

	SATISFIED("🤣", ":satisfied:"),

	THUMBSDOWN("👎", ":thumbsdown:"),

	THUMBSUP("👍", ":thumbsup:"),

	PLUS_ONE("👍", ":+1:"),

	MINUS_ONE("👎", ":-1:"),

	/**
	 * A star rating a person gave an asset, whose value is in {@code reaction.rating}.
	 *
	 * <p>
	 * Not an emoji reaction, but it lives on the same table on purpose: the existing
	 * {@code UNIQUE (creator_uuid, type, asset_uuid)} index then means exactly "one rating per user
	 * per asset", and gives it without colliding with a genuine {@link #SATISFIED}. Before this
	 * constant existed the rating was written as {@code SATISFIED} with a number attached, which made
	 * a star rating and a 🤣 the same row.
	 * </p>
	 */
	RATING("⭐", ":star:");

	private String id;
	private String icon;

	ReactionType(String icon, String id) {
		this.icon = icon;
		this.id = id;
	}

	public String getIcon() {
		return icon;
	}

	public String getId() {
		return id;
	}

}
