package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDayStats;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class PipelineRunDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineRunDao, PipelineRun> {

	@Override
	public PipelineRun createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setMeta(new JsonObject().put("key", "value"));
		pipelineDao().store(pipeline);

		// Create v1 version
		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			1,
			"pipeline_" + i,
			"Test pipeline " + i,
			new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()),
			true,
			i,
			false,
			new JsonObject().put("versionKey", "versionValue")
		);
		pipelineVersionDao().store(version);

		// Update pipeline with latest version reference
		pipeline.setLatestVersionUuid(version.getUuid());
		pipelineDao().update(pipeline);

		PipelineRun run = pipelineRunDao().createPipelineRun(user.getUuid(), pipeline.getUuid(), 1);
		run.setStatus(PipelineRunStatus.SUCCESS);
		run.setMediaCount(100);
		run.setSuccessCount(95);
		run.setFailureCount(3);
		run.setSkippedCount(2);
		run.setDryRun(false);
		run.setDurationMs(45000L);
		run.setErrorMessage(null);
		return run;
	}

	@Override
	public void assertCreate(PipelineRun createdElement) {
		assertEquals(PipelineRunStatus.SUCCESS, createdElement.getStatus());
		assertEquals(100, createdElement.getMediaCount());
		assertEquals(95, createdElement.getSuccessCount());
		assertEquals(3, createdElement.getFailureCount());
		assertEquals(2, createdElement.getSkippedCount());
		assertEquals(false, createdElement.isDryRun());
		assertEquals(45000L, createdElement.getDurationMs());
		assertNull(createdElement.getErrorMessage());
		assertNotNull(createdElement.getPipelineUuid());
		assertEquals(1, createdElement.getPipelineVersion());
	}

	@Override
	public PipelineRunDao getDao() {
		return pipelineRunDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public void updateElement(PipelineRun element) {
		element.setStatus(PipelineRunStatus.FAILED);
		element.setMediaCount(200);
		element.setSuccessCount(150);
		element.setFailureCount(50);
		element.setSkippedCount(0);
		element.setDurationMs(90000L);
		element.setErrorMessage("Processing failed");
	}

	@Override
	public void assertUpdate(PipelineRun updatedElement) {
		assertEquals(PipelineRunStatus.FAILED, updatedElement.getStatus());
		assertEquals(200, updatedElement.getMediaCount());
		assertEquals(150, updatedElement.getSuccessCount());
		assertEquals(50, updatedElement.getFailureCount());
		assertEquals(0, updatedElement.getSkippedCount());
		assertEquals(90000L, updatedElement.getDurationMs());
		assertEquals("Processing failed", updatedElement.getErrorMessage());
	}

	// ── Daily stats aggregation ─────────────────────────────────────────

	/** Persist a run of the given pipeline started at noon (UTC) of the given day. */
	private PipelineRun createRunOn(User user, UUID pipelineUuid, LocalDate day, PipelineRunStatus status, int success, int failure, int skipped) {
		PipelineRun run = pipelineRunDao().createPipelineRun(user.getUuid(), pipelineUuid, 1);
		run.setStatus(status);
		run.setMediaCount(success + failure + skipped);
		run.setSuccessCount(success);
		run.setFailureCount(failure);
		run.setSkippedCount(skipped);
		run.setStarted(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
		pipelineRunDao().store(run);
		return run;
	}

	private Optional<PipelineRunDayStats> bucket(List<PipelineRunDayStats> stats, LocalDate day) {
		return stats.stream().filter(s -> day.equals(s.getDate())).findFirst();
	}

	@Test
	public void testLoadDailyStats() {
		AtomicReference<UUID> pipelineA = new AtomicReference<>();
		AtomicReference<UUID> pipelineB = new AtomicReference<>();
		// Days far in the past so runs created by the generic CRUD testcases (started = now)
		// cannot bleed into the asserted buckets.
		LocalDate dayOld = LocalDate.now().minusDays(32);
		LocalDate dayRecent = LocalDate.now().minusDays(30);

		transaction(tx -> {
			User user = dummyUser();
			PipelineRun seed = createElement(user, 4711);
			pipelineRunDao().store(seed);
			pipelineA.set(seed.getPipelineUuid());
			PipelineRun seedB = createElement(user, 4712);
			pipelineRunDao().store(seedB);
			pipelineB.set(seedB.getPipelineUuid());

			// Two runs on dayRecent across two pipelines, one run on dayOld. dayRecent-1 stays empty.
			createRunOn(user, pipelineA.get(), dayRecent, PipelineRunStatus.SUCCESS, 5, 1, 2);
			createRunOn(user, pipelineB.get(), dayRecent, PipelineRunStatus.PARTIAL, 10, 0, 0);
			createRunOn(user, pipelineA.get(), dayOld, PipelineRunStatus.FAILED, 0, 3, 1);
		});

		List<PipelineRunDayStats> stats = pipelineRunDao().loadDailyStats(dayOld.minusDays(1).atStartOfDay());

		PipelineRunDayStats recent = bucket(stats, dayRecent).orElseThrow();
		assertEquals(2, recent.getRunCount(), "Runs of both pipelines must aggregate into the same day bucket");
		assertEquals(15, recent.getSuccessCount());
		assertEquals(1, recent.getFailureCount());
		assertEquals(2, recent.getSkippedCount());

		PipelineRunDayStats old = bucket(stats, dayOld).orElseThrow();
		assertEquals(1, old.getRunCount());
		assertEquals(0, old.getSuccessCount());
		assertEquals(3, old.getFailureCount());
		assertEquals(1, old.getSkippedCount());

		assertTrue(bucket(stats, dayRecent.minusDays(1)).isEmpty(), "Days without runs must not be returned");

		// Buckets are ordered oldest first.
		for (int i = 1; i < stats.size(); i++) {
			assertTrue(stats.get(i - 1).getDate().isBefore(stats.get(i).getDate()), "Buckets must be ordered oldest first");
		}
	}

	@Test
	public void testLoadDailyStatsWindow() {
		LocalDate dayOutside = LocalDate.now().minusDays(40);
		LocalDate dayInside = LocalDate.now().minusDays(38);

		transaction(tx -> {
			User user = dummyUser();
			PipelineRun seed = createElement(user, 4713);
			pipelineRunDao().store(seed);
			createRunOn(user, seed.getPipelineUuid(), dayOutside, PipelineRunStatus.SUCCESS, 1, 0, 0);
			createRunOn(user, seed.getPipelineUuid(), dayInside, PipelineRunStatus.SUCCESS, 2, 0, 0);
		});

		List<PipelineRunDayStats> stats = pipelineRunDao().loadDailyStats(dayInside.atStartOfDay());

		assertTrue(bucket(stats, dayOutside).isEmpty(), "Runs started before the window must be excluded");
		assertEquals(2, bucket(stats, dayInside).orElseThrow().getSuccessCount());
	}

}