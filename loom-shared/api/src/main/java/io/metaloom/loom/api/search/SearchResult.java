package io.metaloom.loom.api.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer to a {@link SearchRequest}.
 */
public class SearchResult {

	private List<SearchHit> hits = new ArrayList<>();

	/** Matches across all pages. */
	private long totalHits;

	/** Whether {@link #getTotalHits()} is exact rather than capped or estimated. */
	private boolean totalExact = true;

	private long tookMs;

	/**
	 * Opaque cursor for the next page, or null when the provider cannot deep-page. Clients must prefer this over {@code offset} when it is present, so
	 * that a later provider swap gives them unbounded paging with no API change.
	 */
	private String nextCursor;

	private Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();

	private String providerName;

	/**
	 * Non-fatal notes about how the request was answered - most importantly the entity types that were dropped because the caller lacks the matching
	 * read permission. Silently returning fewer types than asked for would be indistinguishable from an empty index.
	 */
	private List<String> warnings = new ArrayList<>();

	public List<SearchHit> getHits() {
		return hits;
	}

	public SearchResult setHits(List<SearchHit> hits) {
		this.hits = hits == null ? new ArrayList<>() : hits;
		return this;
	}

	public SearchResult addHit(SearchHit hit) {
		this.hits.add(hit);
		return this;
	}

	public long getTotalHits() {
		return totalHits;
	}

	public SearchResult setTotalHits(long totalHits) {
		this.totalHits = totalHits;
		return this;
	}

	public boolean isTotalExact() {
		return totalExact;
	}

	public SearchResult setTotalExact(boolean totalExact) {
		this.totalExact = totalExact;
		return this;
	}

	public long getTookMs() {
		return tookMs;
	}

	public SearchResult setTookMs(long tookMs) {
		this.tookMs = tookMs;
		return this;
	}

	public String getNextCursor() {
		return nextCursor;
	}

	public SearchResult setNextCursor(String nextCursor) {
		this.nextCursor = nextCursor;
		return this;
	}

	public Map<String, List<FacetBucket>> getFacets() {
		return facets;
	}

	public SearchResult setFacets(Map<String, List<FacetBucket>> facets) {
		this.facets = facets == null ? new LinkedHashMap<>() : facets;
		return this;
	}

	public String getProviderName() {
		return providerName;
	}

	public SearchResult setProviderName(String providerName) {
		this.providerName = providerName;
		return this;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public SearchResult setWarnings(List<String> warnings) {
		this.warnings = warnings == null ? new ArrayList<>() : warnings;
		return this;
	}

	public SearchResult addWarning(String warning) {
		this.warnings.add(warning);
		return this;
	}

	@Override
	public String toString() {
		return "SearchResult[" + hits.size() + "/" + totalHits + " hits, " + tookMs + "ms, " + providerName + "]";
	}
}
