package io.metaloom.loom.rest.model.searchindex;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One search index as reported by {@code GET /api/v1/search-indices}.
 *
 * <p>
 * {@code documentCount} and {@code indexedCount} are both present because the only question this screen exists to answer is whether they agree.
 * Collapsing them into one number would hide the two failure modes an operator cares about: a backlog (the index is behind) and orphans (the index
 * holds entries whose source rows are gone).
 * </p>
 */
public class SearchIndexResponse implements RestResponseModel<SearchIndexResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Stable, URL-safe identifier used on the job routes, e.g. lexical or vector-face-inspireface-r18-512.")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What kind of index this is: LEXICAL, VECTOR or FINGERPRINT.")
	private String kind;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Which storage backend holds this index. Size on disk is reported per backend, not per index.")
	private String backendId;

	@JsonPropertyDescription("Human readable name, e.g. Face embeddings.")
	private String label;

	@JsonPropertyDescription("The embedding.type this space holds, e.g. face. Null for non-vector indices.")
	private String type;

	@JsonPropertyDescription("The model that produced the vectors, e.g. inspireface-r18. Empty when none was recorded; null for non-vector indices.")
	private String model;

	@JsonPropertyDescription("Vector length. Null for non-vector indices.")
	private Integer dimensions;

	@JsonPropertyDescription("The fingerprint algorithm. Null unless this is the fingerprint index.")
	private String algorithm;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether the backend is switched on at all.")
	private boolean enabled;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether the backend is switched on AND usable. Enabled but unavailable is an operational fault; neither is a choice.")
	private boolean available;

	@JsonPropertyDescription("Why the index is unavailable, or a note about how it is maintained. Null when there is nothing to say.")
	private String reason;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many items the system of record holds - the number the index should match.")
	private long documentCount;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many items the index itself holds. More than documentCount means orphans; fewer means a backlog.")
	private long indexedCount;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Items known to be waiting. Always 0 for the lexical index, which database triggers keep current inside the writing transaction.")
	private long pendingCount;

	@JsonPropertyDescription("When the index was last brought up to date, if the backend tracks it.")
	private Instant lastSyncedAt;

	@JsonPropertyDescription("Which job actions this index accepts: REINDEX, DELTA_SYNC, DROP.")
	private List<String> supportedActions = new ArrayList<>();

	@JsonPropertyDescription("The job currently running against this index, or null.")
	private IndexJobResponse activeJob;

	public String getId() {
		return id;
	}

	public SearchIndexResponse setId(String id) {
		this.id = id;
		return this;
	}

	public String getKind() {
		return kind;
	}

	public SearchIndexResponse setKind(String kind) {
		this.kind = kind;
		return this;
	}

	public String getBackendId() {
		return backendId;
	}

	public SearchIndexResponse setBackendId(String backendId) {
		this.backendId = backendId;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public SearchIndexResponse setLabel(String label) {
		this.label = label;
		return this;
	}

	public String getType() {
		return type;
	}

	public SearchIndexResponse setType(String type) {
		this.type = type;
		return this;
	}

	public String getModel() {
		return model;
	}

	public SearchIndexResponse setModel(String model) {
		this.model = model;
		return this;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public SearchIndexResponse setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public SearchIndexResponse setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public SearchIndexResponse setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public SearchIndexResponse setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public SearchIndexResponse setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public long getDocumentCount() {
		return documentCount;
	}

	public SearchIndexResponse setDocumentCount(long documentCount) {
		this.documentCount = documentCount;
		return this;
	}

	public long getIndexedCount() {
		return indexedCount;
	}

	public SearchIndexResponse setIndexedCount(long indexedCount) {
		this.indexedCount = indexedCount;
		return this;
	}

	public long getPendingCount() {
		return pendingCount;
	}

	public SearchIndexResponse setPendingCount(long pendingCount) {
		this.pendingCount = pendingCount;
		return this;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public SearchIndexResponse setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
		return this;
	}

	public List<String> getSupportedActions() {
		return supportedActions;
	}

	public SearchIndexResponse setSupportedActions(List<String> supportedActions) {
		this.supportedActions = supportedActions;
		return this;
	}

	public IndexJobResponse getActiveJob() {
		return activeJob;
	}

	public SearchIndexResponse setActiveJob(IndexJobResponse activeJob) {
		this.activeJob = activeJob;
		return this;
	}

	@Override
	public SearchIndexResponse self() {
		return this;
	}
}
