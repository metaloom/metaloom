package io.metaloom.loom.rest.search;

import java.util.Set;

/** Lifecycle of an index maintenance job. */
public enum IndexJobState {

	/** Accepted and queued. Jobs run one at a time, so a job can sit here while another finishes. */
	PENDING,

	RUNNING,

	SUCCEEDED,

	FAILED,

	/** Stopped on request. Whatever had already been written stays written - these operations are idempotent, so a cancelled job is a partial one. */
	CANCELLED;

	private static final Set<IndexJobState> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}
}
