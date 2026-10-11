package io.metaloom.cortex.node.filter;

/**
 * Which evidence of a person a {@link FilterBy#PERSON} filter counts.
 *
 * <p>
 * A node option rather than a prefix in the match column, for the reason {@link TagSource} gives: a second grammar inside a field that already has one
 * earns nothing when the choice is nearly always made once for the whole node.
 * </p>
 */
public enum PersonSource {

	/** A person seen or heard - a face cluster or a speaker cluster names them. */
	ANY,

	/** Only a face cluster counts. */
	FACE,

	/** Only a speaker cluster counts. */
	VOICE
}
