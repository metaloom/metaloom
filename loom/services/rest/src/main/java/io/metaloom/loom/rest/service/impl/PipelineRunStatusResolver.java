package io.metaloom.loom.rest.service.impl;

/**
 * Maps the per-media aggregate counters reported by a Cortex processor onto the
 * {@code pipeline_run.status} vocabulary documented in
 * {@code V2.29__add_pipeline_run.sql}.
 *
 * <p>Kept deliberately free of DB and transport concerns so the mapping can be
 * unit-tested in isolation. The status values are plain strings — introducing a
 * typed enum is tracked separately (PIPELINE_TASKS Task 9).</p>
 */
public final class PipelineRunStatusResolver {

	public static final String PENDING = "PENDING";
	public static final String RUNNING = "RUNNING";
	public static final String SUCCESS = "SUCCESS";
	public static final String FAILED = "FAILED";
	public static final String PARTIAL = "PARTIAL";
	public static final String CANCELLED = "CANCELLED";

	private PipelineRunStatusResolver() {
	}

	/**
	 * Resolve the terminal status of a completed run.
	 *
	 * <ul>
	 *   <li>no failures → {@link #SUCCESS} (this includes a run that processed
	 *       nothing at all, and a dry run where every item was skipped)</li>
	 *   <li>every media item failed → {@link #FAILED}</li>
	 *   <li>some failed, some did not → {@link #PARTIAL}</li>
	 * </ul>
	 *
	 * <p>Counters are clamped at zero so a malformed report cannot produce a
	 * nonsensical status.</p>
	 *
	 * @param mediaCount   total media items the run processed
	 * @param failureCount media items that failed
	 * @return one of {@link #SUCCESS}, {@link #PARTIAL}, {@link #FAILED}
	 */
	public static String resolve(int mediaCount, int failureCount) {
		int media = Math.max(0, mediaCount);
		int failures = Math.max(0, failureCount);

		if (failures == 0) {
			return SUCCESS;
		}
		// More failures than media reported means the counters disagree; treat
		// the run as fully failed rather than silently reporting PARTIAL.
		if (failures >= media) {
			return FAILED;
		}
		return PARTIAL;
	}

	/**
	 * Whether the given status is terminal — a run in a terminal state must not
	 * be overwritten by a late-arriving completion or timeout report.
	 */
	public static boolean isTerminal(String status) {
		if (status == null) {
			return false;
		}
		return switch (status) {
			case SUCCESS, FAILED, PARTIAL, CANCELLED -> true;
			default -> false;
		};
	}
}
