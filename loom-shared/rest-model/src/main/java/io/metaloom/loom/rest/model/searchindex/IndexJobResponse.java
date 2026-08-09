package io.metaloom.loom.rest.model.searchindex;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * An index maintenance operation and how far it has got.
 *
 * <p>
 * Jobs live in memory only: a restart loses the record, which is acceptable because a job asks for work that is derivable from the database and can
 * simply be requested again. Nothing is left inconsistent by a lost job.
 * </p>
 */
public class IndexJobResponse implements RestResponseModel<IndexJobResponse> {

	@JsonProperty(required = true)
	private UUID uuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Which index this job runs against.")
	private String indexId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("REINDEX, DELTA_SYNC or DROP.")
	private String action;

	@JsonProperty(required = true)
	@JsonPropertyDescription("PENDING, RUNNING, SUCCEEDED, FAILED or CANCELLED.")
	private String state;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Items written so far.")
	private long processed;

	@JsonPropertyDescription("Items the job expects to handle, or null when that cannot be known - the lexical rebuild is a single SQL call, so a client must render an indeterminate progress bar for it.")
	private Long total;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Entries removed from the index: orphans swept, or everything a drop discarded.")
	private long removed;

	private Instant startedAt;

	private Instant finishedAt;

	@JsonPropertyDescription("Why the job failed. Null unless state is FAILED.")
	private String error;

	public UUID getUuid() {
		return uuid;
	}

	public IndexJobResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getIndexId() {
		return indexId;
	}

	public IndexJobResponse setIndexId(String indexId) {
		this.indexId = indexId;
		return this;
	}

	public String getAction() {
		return action;
	}

	public IndexJobResponse setAction(String action) {
		this.action = action;
		return this;
	}

	public String getState() {
		return state;
	}

	public IndexJobResponse setState(String state) {
		this.state = state;
		return this;
	}

	public long getProcessed() {
		return processed;
	}

	public IndexJobResponse setProcessed(long processed) {
		this.processed = processed;
		return this;
	}

	public Long getTotal() {
		return total;
	}

	public IndexJobResponse setTotal(Long total) {
		this.total = total;
		return this;
	}

	public long getRemoved() {
		return removed;
	}

	public IndexJobResponse setRemoved(long removed) {
		this.removed = removed;
		return this;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public IndexJobResponse setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
		return this;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public IndexJobResponse setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
		return this;
	}

	public String getError() {
		return error;
	}

	public IndexJobResponse setError(String error) {
		this.error = error;
		return this;
	}

	@Override
	public IndexJobResponse self() {
		return this;
	}
}
