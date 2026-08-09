package io.metaloom.loom.rest.model.searchindex;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The storage one or more indices share.
 *
 * <p>
 * Backends are reported separately from indices because <b>size on disk has no per-index meaning</b>: the embedding vector index is a single Lucene
 * directory whose segments interleave every vector space in it. Splitting the bytes by document share would look authoritative and be invented, so
 * they are attributed to the directory that actually holds them.
 * </p>
 */
public class SearchIndexBackendResponse implements RestResponseModel<SearchIndexBackendResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Identifier referenced by SearchIndexResponse.backendId: lexical, vector or fingerprint.")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The bound implementation, e.g. postgres, lucene or none.")
	private String provider;

	@JsonProperty(required = true)
	private boolean enabled;

	@JsonProperty(required = true)
	private boolean available;

	@JsonPropertyDescription("Why the backend is unavailable, or a note about how it is maintained.")
	private String reason;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Live entries across every index in this backend.")
	private long documentCount;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Entries deleted but not yet merged away. This is why a drop does not immediately shrink sizeBytes - Lucene deletes are logical.")
	private long deletedCount;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Bytes on disk, including space still held by deleted-but-unmerged entries.")
	private long sizeBytes;

	public String getId() {
		return id;
	}

	public SearchIndexBackendResponse setId(String id) {
		this.id = id;
		return this;
	}

	public String getProvider() {
		return provider;
	}

	public SearchIndexBackendResponse setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public SearchIndexBackendResponse setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public SearchIndexBackendResponse setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public SearchIndexBackendResponse setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public long getDocumentCount() {
		return documentCount;
	}

	public SearchIndexBackendResponse setDocumentCount(long documentCount) {
		this.documentCount = documentCount;
		return this;
	}

	public long getDeletedCount() {
		return deletedCount;
	}

	public SearchIndexBackendResponse setDeletedCount(long deletedCount) {
		this.deletedCount = deletedCount;
		return this;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public SearchIndexBackendResponse setSizeBytes(long sizeBytes) {
		this.sizeBytes = sizeBytes;
		return this;
	}

	@Override
	public SearchIndexBackendResponse self() {
		return this;
	}
}
