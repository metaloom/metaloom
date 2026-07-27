package io.metaloom.loom.api.search;

/**
 * A capability a {@link SearchProvider} either has or does not have.
 *
 * <p>
 * This is what lets the REST layer degrade <b>honestly</b> rather than silently. The Postgres provider does not advertise {@link #DEEP_PAGING}, so a
 * request past its offset cap is rejected with a 400 that names the provider - instead of running an {@code OFFSET 50000} table scan until the request
 * times out. Likewise a {@link SearchMode#SEMANTIC} request against a provider without {@link #SEMANTIC} is rejected rather than quietly answered with
 * lexical results, because a silent fallback makes relevance bugs undiagnosable.
 * </p>
 */
public enum SearchCapability {

	/** Full-text matching. Every real provider has this. */
	LEXICAL,

	/** Quoted phrase queries. */
	PHRASE,

	/** Typo tolerance (trigram similarity, or an edit-distance query). */
	FUZZY,

	/** Match snippets on hits. */
	HIGHLIGHT,

	/** Facet/aggregation counts. */
	FACETS,

	/** {@code totalHits} is exact rather than an estimate or a cap. */
	EXACT_TOTAL,

	/** Paging beyond the provider's offset cap, via an opaque cursor. */
	DEEP_PAGING,

	/** Vector similarity over embeddings. */
	SEMANTIC,

	/** Fused lexical + vector ranking. */
	HYBRID,

	/** Prefix typeahead suggestions. */
	SUGGEST
}
