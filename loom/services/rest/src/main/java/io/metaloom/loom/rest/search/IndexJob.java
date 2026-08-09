package io.metaloom.loom.rest.search;

import java.time.Instant;
import java.util.UUID;

/**
 * One index maintenance operation and its progress.
 *
 * <p>
 * Mutable and read from a different thread than the one that writes it - the worker updates the counters while the polling route reads them - so
 * every field a reader sees is {@code volatile}. There is no lock: a reader that catches {@code processed} a few rows behind {@code total} is showing
 * a progress bar, and pausing the work to make that number exact would be the wrong trade.
 * </p>
 */
public class IndexJob {

	private final UUID uuid = UUID.randomUUID();

	private final String indexId;

	private final IndexJobAction action;

	private volatile IndexJobState state = IndexJobState.PENDING;

	private volatile long processed;

	/**
	 * How many items the job expects to handle, or {@code null} when that cannot be known.
	 *
	 * <p>
	 * Nullable rather than -1 because it is genuinely absent for the lexical rebuild: that is a single SQL call which either finishes or does not, and
	 * reporting a fabricated total would make the client draw a determinate bar over an operation whose duration it cannot predict.
	 * </p>
	 */
	private volatile Long total;

	/** Entries removed from the index - orphans swept, or everything a drop discarded. Separate from {@link #processed}, which counts writes. */
	private volatile long removed;

	private volatile Instant startedAt;

	private volatile Instant finishedAt;

	private volatile String error;

	private volatile boolean cancelRequested;

	public IndexJob(String indexId, IndexJobAction action) {
		this.indexId = indexId;
		this.action = action;
	}

	public UUID getUuid() {
		return uuid;
	}

	public String getIndexId() {
		return indexId;
	}

	public IndexJobAction getAction() {
		return action;
	}

	public IndexJobState getState() {
		return state;
	}

	public IndexJob setState(IndexJobState state) {
		this.state = state;
		return this;
	}

	public long getProcessed() {
		return processed;
	}

	public IndexJob setProcessed(long processed) {
		this.processed = processed;
		return this;
	}

	/** Advance the counter by one. Called once per item from the worker thread; nothing else writes it. */
	public void incrementProcessed() {
		processed++;
	}

	public Long getTotal() {
		return total;
	}

	public IndexJob setTotal(Long total) {
		this.total = total;
		return this;
	}

	public long getRemoved() {
		return removed;
	}

	public IndexJob setRemoved(long removed) {
		this.removed = removed;
		return this;
	}

	public void addRemoved(long count) {
		this.removed += count;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public IndexJob setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
		return this;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public IndexJob setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
		return this;
	}

	public String getError() {
		return error;
	}

	public IndexJob setError(String error) {
		this.error = error;
		return this;
	}

	public boolean isCancelRequested() {
		return cancelRequested;
	}

	/**
	 * Ask the job to stop at the next item boundary.
	 *
	 * <p>
	 * Cooperative rather than an interrupt: these jobs hold a Lucene write lock and a database cursor, and tearing either down mid-operation is how a
	 * cancelled rebuild becomes a corrupt index. The cost is that a job blocked inside one long call - the lexical rebuild is one SQL statement -
	 * finishes that call before it notices.
	 * </p>
	 */
	public void requestCancel() {
		this.cancelRequested = true;
	}
}
