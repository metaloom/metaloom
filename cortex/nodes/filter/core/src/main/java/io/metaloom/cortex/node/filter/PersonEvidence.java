package io.metaloom.cortex.node.filter;

/**
 * How sure a {@link FilterBy#PERSON} filter must be that a person is in an item.
 */
public enum PersonEvidence {

	/**
	 * Only what a reviewer confirmed. The default: a confirmed cluster is a human's statement that this face or voice is this person, and routing on
	 * anything less is routing on a machine's guess about a biometric identity.
	 */
	CONFIRMED,

	/**
	 * Confirmed clusters, plus clusters person matching suggested and nobody has rejected. For triage - "probably Anna" - never for anything that
	 * publishes a name.
	 */
	SUGGESTED
}
