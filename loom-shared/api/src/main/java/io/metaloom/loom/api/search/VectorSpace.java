package io.metaloom.loom.api.search;

/**
 * The identity of a set of comparable vectors: what they describe, what produced them, and how long they are.
 *
 * <p>
 * <b>This triple is the answer to "the embedding model will change."</b> Every write and every query carries it, and implementations keep each space
 * separate, so upgrading a model does not invalidate the vectors already stored - the new ones land in a new space beside the old, both are queryable,
 * and the old space is dropped when its replacement has been shown to be better. Nothing has to be migrated for that to work.
 * </p>
 *
 * <p>
 * It also makes the one silent failure this area is prone to impossible: a query can never be answered with a neighbour from a different model. Two
 * models produce vectors of the same length in the same datatype and mean completely different things by them, and a distance between them is a
 * plausible-looking number with no meaning at all.
 * </p>
 *
 * @param type
 *            what the vector describes, matching {@code embedding.type} - e.g. {@code face}, {@code clip}. Free text on purpose: a new kind of
 *            embedding must not need a code change to be storable.
 * @param model
 *            the producing model, matching {@code embedding.model} - e.g. {@code inspireface-r18}
 * @param dimensions
 *            the vector length, matching {@code embedding.dimensions}
 */
public record VectorSpace(String type, String model, int dimensions) {

	public VectorSpace {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("A vector space needs a type");
		}
		if (dimensions <= 0) {
			throw new IllegalArgumentException("A vector space needs a positive dimension count, got " + dimensions);
		}
		// An unknown model is the empty string rather than null, mirroring embedding.model NOT NULL DEFAULT ''.
		// This keeps the record usable as a map key without every caller having to null-check it.
		model = model == null ? "" : model;
	}

	/**
	 * A stable key for this space, suitable for naming an index segment, a Lucene filter term or a directory.
	 */
	public String key() {
		return type + "/" + model + "/" + dimensions;
	}
}
