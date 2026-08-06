package io.metaloom.loom.rest.service.impl;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;

/**
 * Maps the per-media aggregate counters reported by a Cortex processor onto
 * {@link PipelineRunStatus}.
 *
 * <p>Kept deliberately free of DB and transport concerns so the mapping can be
 * unit-tested in isolation.</p>
 */
public final class PipelineRunStatusResolver {

	private PipelineRunStatusResolver() {
	}

	/**
	 * Resolve the terminal status of a completed run.
	 *
	 * <ul>
	 *   <li>no failures → {@link PipelineRunStatus#SUCCESS} (this includes a run that
	 *       processed nothing at all, and a dry run where every item was skipped)</li>
	 *   <li>every media item failed → {@link PipelineRunStatus#FAILED}</li>
	 *   <li>some failed, some did not → {@link PipelineRunStatus#PARTIAL}</li>
	 * </ul>
	 *
	 * <p>Counters are clamped at zero so a malformed report cannot produce a
	 * nonsensical status.</p>
	 *
	 * @param mediaCount   total media items the run processed
	 * @param failureCount media items that failed
	 * @return SUCCESS, PARTIAL or FAILED
	 */
	public static PipelineRunStatus resolve(int mediaCount, int failureCount) {
		int media = Math.max(0, mediaCount);
		int failures = Math.max(0, failureCount);

		if (failures == 0) {
			return PipelineRunStatus.SUCCESS;
		}
		// More failures than media reported means the counters disagree; treat
		// the run as fully failed rather than silently reporting PARTIAL.
		if (failures >= media) {
			return PipelineRunStatus.FAILED;
		}
		return PipelineRunStatus.PARTIAL;
	}

	/**
	 * Whether the given status is terminal — a run in a terminal state must not
	 * be overwritten by a late-arriving completion or timeout report.
	 *
	 * <p>Null-tolerant, because a run row is read before its status is known to be set;
	 * {@link PipelineRunStatus#isTerminal()} is the answer for everything else, and
	 * {@code PAUSED} is deliberately not terminal.</p>
	 */
	public static boolean isTerminal(PipelineRunStatus status) {
		return status != null && status.isTerminal();
	}
}
