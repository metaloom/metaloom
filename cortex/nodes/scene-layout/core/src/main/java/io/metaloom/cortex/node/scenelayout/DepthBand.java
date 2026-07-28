package io.metaloom.cortex.node.scenelayout;

/**
 * Where an object sits in the scene's depth range.
 *
 * <p>
 * Bands are cut from quantiles of the <em>whole scene's</em> depth histogram rather than
 * from the detected objects' own depths. Banding is meant to describe where something sits
 * in the picture; ranking only the objects against each other would label the nearer of two
 * equally distant background faces "foreground", which is wrong in a way that reads as a bug.
 * </p>
 */
public enum DepthBand {

	FOREGROUND("is in the foreground"),

	MIDGROUND("is in the midground"),

	BACKGROUND("is in the background");

	private final String phrase;

	DepthBand(String phrase) {
		this.phrase = phrase;
	}

	/**
	 * The English form used to build the readable {@code phrases} array.
	 *
	 * @return the verb phrase, e.g. {@code "is in the foreground"}
	 */
	public String phrase() {
		return phrase;
	}
}
