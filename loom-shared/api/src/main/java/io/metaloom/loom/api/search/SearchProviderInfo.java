package io.metaloom.loom.api.search;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What {@code GET /api/v1/search/status} reports.
 *
 * <p>
 * This is answered with 200 even when search is unavailable, so the UI can hide the search bar instead of rendering one that 503s on every keystroke.
 * </p>
 */
public class SearchProviderInfo {

	private String provider;

	private boolean available;

	/** Why search is unavailable, when it is. Null when {@link #isAvailable()}. */
	private String reason;

	private Set<SearchCapability> capabilities = new LinkedHashSet<>();

	private long documentCount;

	/** Documents waiting to be pushed to an external index. Always 0 for the Postgres provider, which has no lag by construction. */
	private long dirtyCount;

	private Instant lastSyncedAt;

	public String getProvider() {
		return provider;
	}

	public SearchProviderInfo setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public SearchProviderInfo setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public SearchProviderInfo setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public Set<SearchCapability> getCapabilities() {
		return capabilities;
	}

	public SearchProviderInfo setCapabilities(Set<SearchCapability> capabilities) {
		this.capabilities = capabilities == null ? new LinkedHashSet<>() : capabilities;
		return this;
	}

	public long getDocumentCount() {
		return documentCount;
	}

	public SearchProviderInfo setDocumentCount(long documentCount) {
		this.documentCount = documentCount;
		return this;
	}

	public long getDirtyCount() {
		return dirtyCount;
	}

	public SearchProviderInfo setDirtyCount(long dirtyCount) {
		this.dirtyCount = dirtyCount;
		return this;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public SearchProviderInfo setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
		return this;
	}
}
