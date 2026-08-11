package io.metaloom.loom.db.model.share;

/**
 * What a share visitor can say without typing.
 *
 * <p>
 * Deliberately <b>not</b> {@code io.metaloom.loom.api.reaction.ReactionType}. That vocabulary belongs to the internal social features and gained a
 * value for reasons that have nothing to do with a client signing off a cut (V2.78 added {@code RATING} after the workflow star rating collided with
 * genuine reactions in a shared unique index). Keeping the two lists apart means neither can churn the other, and it keeps a client's approval from
 * ever being counted as a colleague's thumbs-up.
 * </p>
 *
 * <p>
 * {@code APPROVE} and {@code REJECT} lead the list because sign-off, not sentiment, is what a review link is for.
 * </p>
 */
public enum ShareReactionType {

	APPROVE,

	REJECT,

	THUMBSUP,

	THUMBSDOWN,

	LOVE,

	QUESTION;

	public static ShareReactionType parse(String value) {
		if (value == null) {
			return null;
		}
		for (ShareReactionType type : values()) {
			if (type.name().equals(value)) {
				return type;
			}
		}
		return null;
	}
}
