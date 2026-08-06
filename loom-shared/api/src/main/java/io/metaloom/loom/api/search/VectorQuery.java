package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * A nearest-neighbour query against one {@link VectorSpace}.
 *
 * @param space
 *            which set of comparable vectors to search. Required: a query without one would have to guess which model the caller meant
 * @param vector
 *            the query vector; its length must equal {@code space.dimensions()}
 * @param limit
 *            maximum neighbours to return
 * @param scoreThreshold
 *            hits scoring below this are dropped
 * @param excludeAssetUuid
 *            an asset to leave out of the results, or null. Set it to the query's own asset for "find other assets with this face" - without it the
 *            best match is always the query vector itself
 */
public record VectorQuery(VectorSpace space, float[] vector, int limit, float scoreThreshold, UUID excludeAssetUuid) {

	public VectorQuery {
		if (space == null) {
			throw new IllegalArgumentException("A vector query needs a space - see VectorSpace");
		}
		if (vector == null || vector.length != space.dimensions()) {
			throw new IllegalArgumentException("Query vector length " + (vector == null ? "null" : vector.length)
				+ " disagrees with the space dimension " + space.dimensions() + " for " + space.key());
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("A vector query needs a positive limit, got " + limit);
		}
	}

	public VectorQuery(VectorSpace space, float[] vector, int limit, float scoreThreshold) {
		this(space, vector, limit, scoreThreshold, null);
	}
}
