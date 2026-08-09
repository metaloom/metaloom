package io.metaloom.loom.rest.search;

/**
 * What kind of thing an index is, which decides how it is counted, rebuilt and dropped.
 *
 * <p>
 * These are genuinely different mechanisms rather than three configurations of one: the lexical index is a Postgres table maintained by triggers
 * inside the writing transaction, the vector spaces are Lucene HNSW segments fed asynchronously from a dirty flag, and the fingerprint index is a
 * Lucene k-NN index with no freshness tracking at all. The admin screen shows them side by side because an operator thinks of them together; the code
 * keeps them apart because almost nothing about them is shared.
 * </p>
 */
public enum SearchIndexKind {

	/** The {@code search_document} table behind lexical search. */
	LEXICAL,

	/** One {@code (type, model, dimensions)} space inside the embedding vector index - face vectors, search-text vectors, whatever a node writes. */
	VECTOR,

	/** The perceptual fingerprint k-NN index behind near-duplicate detection. */
	FINGERPRINT
}
