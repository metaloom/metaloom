package io.metaloom.loom.api.search;

import java.time.Instant;

/**
 * State of the write side of an index.
 */
public class IndexStatus {

	private boolean healthy;

	private long documentCount;

	private long pendingCount;

	private long failedCount;

	private Instant lastSyncedAt;

	private String detail;

	public boolean isHealthy() {
		return healthy;
	}

	public IndexStatus setHealthy(boolean healthy) {
		this.healthy = healthy;
		return this;
	}

	public long getDocumentCount() {
		return documentCount;
	}

	public IndexStatus setDocumentCount(long documentCount) {
		this.documentCount = documentCount;
		return this;
	}

	public long getPendingCount() {
		return pendingCount;
	}

	public IndexStatus setPendingCount(long pendingCount) {
		this.pendingCount = pendingCount;
		return this;
	}

	public long getFailedCount() {
		return failedCount;
	}

	public IndexStatus setFailedCount(long failedCount) {
		this.failedCount = failedCount;
		return this;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public IndexStatus setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
		return this;
	}

	public String getDetail() {
		return detail;
	}

	public IndexStatus setDetail(String detail) {
		this.detail = detail;
		return this;
	}
}
