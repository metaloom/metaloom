package io.metaloom.loom.api.search;

import java.time.Instant;

/**
 * State of the write side of an index.
 *
 * <p>
 * Used both for a whole backend (a Lucene directory, the {@code search_document} table) and for one {@link VectorSpace} inside a backend. Not every
 * field is meaningful in both cases: {@link #getSizeBytes()} and {@link #getDeletedCount()} are properties of a <b>backend</b>, because Lucene
 * segments interleave the spaces stored in one directory and there is no per-space byte figure to report. A per-space status leaves them at 0.
 * </p>
 */
public class IndexStatus {

	private boolean healthy;

	private long documentCount;

	private long pendingCount;

	private long failedCount;

	/**
	 * Documents marked deleted but not yet merged away. Lucene deletes are logical, so this is the gap between {@code maxDoc} and {@code numDocs} and
	 * explains why {@link #getSizeBytes()} does not shrink immediately after a drop.
	 */
	private long deletedCount;

	/** Bytes the backend occupies on disk, including the space still held by deleted-but-unmerged documents. */
	private long sizeBytes;

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

	public long getDeletedCount() {
		return deletedCount;
	}

	public IndexStatus setDeletedCount(long deletedCount) {
		this.deletedCount = deletedCount;
		return this;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public IndexStatus setSizeBytes(long sizeBytes) {
		this.sizeBytes = sizeBytes;
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
