package io.metaloom.loom.rest.model.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response of {@code GET /api/v1/search/status}.
 *
 * <p>
 * Answered with 200 even when search is unavailable, so a client can hide its search UI rather than render a control that errors on every keystroke.
 * </p>
 */
public class SearchStatusResponse implements RestResponseModel<SearchStatusResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Which search backend is bound: postgres, elasticsearch or none.")
	private String provider;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether queries can be served right now.")
	private boolean available;

	@JsonPropertyDescription("Why search is unavailable. Null when available.")
	private String reason;

	@JsonPropertyDescription("What the bound backend can do.")
	private List<String> capabilities = new ArrayList<>();

	@JsonPropertyDescription("Number of indexed documents.")
	private long documentCount;

	@JsonPropertyDescription("Documents waiting to be pushed to an external index. Always 0 for the postgres provider, which queries the index directly.")
	private long dirtyCount;

	@JsonPropertyDescription("When the index was last brought up to date.")
	private Instant lastSyncedAt;

	public String getProvider() {
		return provider;
	}

	public SearchStatusResponse setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public SearchStatusResponse setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public SearchStatusResponse setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public List<String> getCapabilities() {
		return capabilities;
	}

	public SearchStatusResponse setCapabilities(List<String> capabilities) {
		this.capabilities = capabilities;
		return this;
	}

	public long getDocumentCount() {
		return documentCount;
	}

	public SearchStatusResponse setDocumentCount(long documentCount) {
		this.documentCount = documentCount;
		return this;
	}

	public long getDirtyCount() {
		return dirtyCount;
	}

	public SearchStatusResponse setDirtyCount(long dirtyCount) {
		this.dirtyCount = dirtyCount;
		return this;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public SearchStatusResponse setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
		return this;
	}

	@Override
	public SearchStatusResponse self() {
		return this;
	}
}
