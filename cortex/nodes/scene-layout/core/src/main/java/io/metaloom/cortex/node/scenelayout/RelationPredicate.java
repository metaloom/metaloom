package io.metaloom.cortex.node.scenelayout;

/**
 * The spatial relations {@link RelationSolver} can assert between two detected objects.
 *
 * <p>
 * Each predicate reads subject-first: {@code (subject, predicate, object)}, so
 * {@code (person-0, IN_FRONT_OF, car-0)} means the person is in front of the car.
 * </p>
 */
public enum RelationPredicate {

	/** The subject is measurably nearer the camera than the object. */
	IN_FRONT_OF("is in front of"),

	/** The subject is measurably farther from the camera than the object. */
	BEHIND("is behind"),

	/** The two are at indistinguishable depth - the difference is within the objects' own measurement noise. */
	SAME_DEPTH("is at the same depth as"),

	/** The subject overlaps the object in the image <em>and</em> is nearer, so it hides part of it. */
	OCCLUDES("occludes"),

	/** The inverse of {@link #OCCLUDES}. */
	OCCLUDED_BY("is occluded by"),

	/** The object's box lies almost entirely inside the subject's. */
	CONTAINS("contains"),

	/** The inverse of {@link #CONTAINS}. */
	INSIDE("is inside"),

	LEFT_OF("is left of"),

	RIGHT_OF("is right of"),

	ABOVE("is above"),

	BELOW("is below"),

	/** Close together in the image plane and at the same depth. */
	NEXT_TO("is next to");

	private final String phrase;

	RelationPredicate(String phrase) {
		this.phrase = phrase;
	}

	/**
	 * The English form used to build the readable {@code phrases} array.
	 *
	 * @return the verb phrase, e.g. {@code "is in front of"}
	 */
	public String phrase() {
		return phrase;
	}
}
