package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;

/**
 * Owns the transition of a {@code pipeline_run} row out of {@code RUNNING}.
 *
 * <p>Two independent paths can close a run out, and they can race:</p>
 * <ul>
 *   <li>the {@code PIPELINE_RUN_COMPLETED} message a processor sends when the
 *       pipeline finishes (the normal path), and</li>
 *   <li>the dispatch watchdog in {@code PipelineEndpointService}, which fires
 *       when a processor never acknowledges the work order at all.</li>
 * </ul>
 *
 * <p>Both funnel through here so the "first terminal verdict wins" rule is
 * enforced in one place — a late watchdog must never overwrite a real result.</p>
 */
@Singleton
public class PipelineRunTracker {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunTracker.class);

	private final PipelineRunDao pipelineRunDao;

	@Inject
	public PipelineRunTracker(PipelineRunDao pipelineRunDao) {
		this.pipelineRunDao = pipelineRunDao;
	}

	/**
	 * Close a run out with the given counters, deriving the status from them.
	 *
	 * @param runUuid     the run to close
	 * @param durationMs  wall-clock duration reported by the processor, may be {@code null}
	 * @param mediaCount  media items processed
	 * @param successCount media items that succeeded
	 * @param failureCount media items that failed
	 * @param skippedCount media items skipped (dry-run or filtered out entirely)
	 * @return true if the run was updated, false if it was missing or already terminal
	 */
	public boolean complete(UUID runUuid, Long durationMs, int mediaCount, int successCount,
			int failureCount, int skippedCount) {
		String status = PipelineRunStatusResolver.resolve(mediaCount, failureCount);
		return apply(runUuid, status, durationMs, mediaCount, successCount, failureCount, skippedCount, null);
	}

	/**
	 * Close a run out as {@code FAILED} — used when the work order itself failed
	 * or was never acknowledged. Counters are left at zero because no media was
	 * reported as processed.
	 *
	 * @param runUuid      the run to close
	 * @param errorMessage why the run failed
	 * @return true if the run was updated, false if it was missing or already terminal
	 */
	public boolean fail(UUID runUuid, String errorMessage) {
		return apply(runUuid, PipelineRunStatusResolver.FAILED, null, 0, 0, 0, 0, errorMessage);
	}

	private boolean apply(UUID runUuid, String status, Long durationMs, int mediaCount,
			int successCount, int failureCount, int skippedCount, String errorMessage) {
		if (runUuid == null) {
			return false;
		}
		try {
			PipelineRun run = pipelineRunDao.load(runUuid);
			if (run == null) {
				log.warn("Cannot close out unknown pipeline run {}", runUuid);
				return false;
			}
			if (PipelineRunStatusResolver.isTerminal(run.getStatus())) {
				log.debug("Pipeline run {} is already terminal ({}) — ignoring {} report",
					runUuid, run.getStatus(), status);
				return false;
			}

			run.setStatus(status)
				.setFinished(Instant.now())
				.setMediaCount(mediaCount)
				.setSuccessCount(successCount)
				.setFailureCount(failureCount)
				.setSkippedCount(skippedCount);
			if (durationMs != null) {
				run.setDurationMs(durationMs);
			}
			if (errorMessage != null) {
				run.setErrorMessage(errorMessage);
			}
			// update(), not store() — store() is INSERT-only on the jOOQ DAOs and
			// would fail the primary key constraint on an existing row.
			pipelineRunDao.update(run);

			log.info("Pipeline run {} closed as {} (media={}, success={}, failure={}, skipped={}, duration={}ms)",
				runUuid, status, mediaCount, successCount, failureCount, skippedCount, durationMs);
			return true;
		} catch (Exception e) {
			// A persistence failure must not tear down the caller — the processor
			// WebSocket and the REST dispatch path both keep running.
			log.error("Failed to persist completion for pipeline run {}", runUuid, e);
			return false;
		}
	}
}
