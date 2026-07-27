package io.metaloom.loom.rest.model.search;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Paging and provenance block of a search response.
 *
 * <p>
 * Deliberately not {@code PagingInfo}: search paging is offset/cursor based rather than keyset, there is no "last uuid" to seek from, and the response
 * has to report which provider answered and what it could not do.
 * </p>
 */
public class SearchMetaInfo {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Total number of matches across all pages.")
	private long totalHits;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether totalHits is exact rather than capped or estimated.")
	private boolean totalExact = true;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Number of elements which can be included in a single page.")
	private int perPage;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Offset of the first returned element.")
	private int offset;

	@JsonPropertyDescription("Opaque cursor for the next page. Prefer it over offset when present - a provider that supports deep paging returns one and offset-based paging may be capped.")
	private String nextCursor;

	@JsonPropertyDescription("Server side query duration in milliseconds.")
	private long tookMs;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Which search backend answered: postgres, elasticsearch or none.")
	private String provider;

	@JsonPropertyDescription("What that backend can do. Clients use this to hide unsupported controls instead of issuing requests that will be rejected.")
	private List<String> capabilities = new ArrayList<>();

	@JsonPropertyDescription("Non-fatal notes, most importantly the entity types that were dropped because the caller lacks the matching read permission.")
	private List<String> warnings = new ArrayList<>();

	public long getTotalHits() {
		return totalHits;
	}

	public SearchMetaInfo setTotalHits(long totalHits) {
		this.totalHits = totalHits;
		return this;
	}

	public boolean isTotalExact() {
		return totalExact;
	}

	public SearchMetaInfo setTotalExact(boolean totalExact) {
		this.totalExact = totalExact;
		return this;
	}

	public int getPerPage() {
		return perPage;
	}

	public SearchMetaInfo setPerPage(int perPage) {
		this.perPage = perPage;
		return this;
	}

	public int getOffset() {
		return offset;
	}

	public SearchMetaInfo setOffset(int offset) {
		this.offset = offset;
		return this;
	}

	public String getNextCursor() {
		return nextCursor;
	}

	public SearchMetaInfo setNextCursor(String nextCursor) {
		this.nextCursor = nextCursor;
		return this;
	}

	public long getTookMs() {
		return tookMs;
	}

	public SearchMetaInfo setTookMs(long tookMs) {
		this.tookMs = tookMs;
		return this;
	}

	public String getProvider() {
		return provider;
	}

	public SearchMetaInfo setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public List<String> getCapabilities() {
		return capabilities;
	}

	public SearchMetaInfo setCapabilities(List<String> capabilities) {
		this.capabilities = capabilities;
		return this;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public SearchMetaInfo setWarnings(List<String> warnings) {
		this.warnings = warnings;
		return this;
	}
}
