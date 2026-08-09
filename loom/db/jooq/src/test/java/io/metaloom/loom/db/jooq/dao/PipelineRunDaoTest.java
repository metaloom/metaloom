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

import io.metaloom.loom.api.pipeline.PipelineRunKind;
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

	// ── Ad-hoc runs ─────────────────────────────────────────────────────

	@Test
	public void testCreateAdhocRunHasNullPipelineUuidAndCarriesItsDefinition() {
		JsonObject definition = new JsonObject().put("version", 1).put("name", "probe sha512");
		AtomicReference<UUID> runUuid = new AtomicReference<>();

		transaction(tx -> {
			PipelineRun run = pipelineRunDao().createAdhocRun(dummyUser().getUuid(), definition);
			run.setStatus(PipelineRunStatus.RUNNING);
			pipelineRunDao().store(run);
			runUuid.set(run.getUuid());
		});

		PipelineRun reloaded = pipelineRunDao().load(runUuid.get());
		assertEquals(PipelineRunKind.ADHOC, reloaded.getKind());
		assertNull(reloaded.getPipelineUuid(), "An ad-hoc run must not name a pipeline");
		// The definition is what recovery rebuilds the graph from after a restart; if it does not
		// round-trip, an ad-hoc run cannot survive one.
		assertEquals(definition, reloaded.getMeta().getJsonObject(PipelineRun.META_DEFINITION));
	}

	@Test
	public void testExistingRunsDefaultToKindPipeline() {
		AtomicReference<UUID> runUuid = new AtomicReference<>();
		transaction(tx -> {
			PipelineRun run = createElement(dummyUser(), 4714);
			pipelineRunDao().store(run);
			runUuid.set(run.getUuid());
		});

		// Rows written before V2.83 carry the column default. A catalog run must keep reading back as
		// PIPELINE, or every existing run would start behaving like an ad-hoc one.
		assertEquals(PipelineRunKind.PIPELINE, pipelineRunDao().load(runUuid.get()).getKind());
	}

	@Test
	public void testAdhocRunSurvivesPipelineDelete() {
		AtomicReference<UUID> pipelineUuid = new AtomicReference<>();
		AtomicReference<UUID> catalogRunUuid = new AtomicReference<>();
		AtomicReference<UUID> adhocRunUuid = new AtomicReference<>();

		transaction(tx -> {
			User user = dummyUser();
			PipelineRun catalogRun = createElement(user, 4715);
			pipelineRunDao().store(catalogRun);
			pipelineUuid.set(catalogRun.getPipelineUuid());
			catalogRunUuid.set(catalogRun.getUuid());

			PipelineRun adhocRun = pipelineRunDao().createAdhocRun(user.getUuid(), new JsonObject().put("version", 1));
			pipelineRunDao().store(adhocRun);
			adhocRunUuid.set(adhocRun.getUuid());
		});

		transaction(tx -> {
			pipelineVersionDao().loadByPipeline(pipelineUuid.get()).forEach(v -> pipelineVersionDao().delete(v.getUuid()));
			pipelineDao().delete(pipelineUuid.get());
		});

		assertNull(pipelineRunDao().load(catalogRunUuid.get()), "A run of the deleted pipeline must cascade away");
		assertNotNull(pipelineRunDao().load(adhocRunUuid.get()),
			"An ad-hoc run belongs to no pipeline and must survive an unrelated pipeline being deleted");
	}

	@Test
	public void testCountActiveAdhocByCreatorIgnoresTerminalAndForeignRuns() {
		AtomicReference<UUID> ownerUuid = new AtomicReference<>();

		transaction(tx -> {
			User owner = dummyUser();
			ownerUuid.set(owner.getUuid());

			PipelineRun running = pipelineRunDao().createAdhocRun(owner.getUuid(), new JsonObject());
			running.setStatus(PipelineRunStatus.RUNNING);
			pipelineRunDao().store(running);

			PipelineRun paused = pipelineRunDao().createAdhocRun(owner.getUuid(), new JsonObject());
			paused.setStatus(PipelineRunStatus.PAUSED);
			pipelineRunDao().store(paused);

			PipelineRun finished = pipelineRunDao().createAdhocRun(owner.getUuid(), new JsonObject());
			finished.setStatus(PipelineRunStatus.SUCCESS);
			pipelineRunDao().store(finished);

			// A catalog run of the same user must not count against the ad-hoc quota.
			PipelineRun catalogRun = createElement(owner, 4716);
			catalogRun.setStatus(PipelineRunStatus.RUNNING);
			pipelineRunDao().store(catalogRun);
		});

		// PAUSED is deliberately not terminal - a paused run still holds an engine and still occupies
		// the quota it was admitted under.
		assertEquals(2, pipelineRunDao().countActiveAdhocByCreator(ownerUuid.get()));
	}

	@Test
	public void testLoadAdhocPageByCreatorExcludesForeignAndCatalogRuns() {
		AtomicReference<UUID> ownerUuid = new AtomicReference<>();
		AtomicReference<UUID> ownRunUuid = new AtomicReference<>();

		transaction(tx -> {
			User owner = dummyUser();
			ownerUuid.set(owner.getUuid());

			PipelineRun own = pipelineRunDao().createAdhocRun(owner.getUuid(), new JsonObject());
			own.setStatus(PipelineRunStatus.RUNNING);
			pipelineRunDao().store(own);
			ownRunUuid.set(own.getUuid());

			PipelineRun catalogRun = createElement(owner, 4717);
			pipelineRunDao().store(catalogRun);
		});

		List<PipelineRun> page = new java.util.ArrayList<>();
		pipelineRunDao().loadAdhocPageByCreator(ownerUuid.get(), null, 25, List.of(), null, null).forEach(page::add);

		assertEquals(1, page.size(), "Only the caller's ad-hoc runs may be listed");
		assertEquals(ownRunUuid.get(), page.get(0).getUuid());
	}

	@Test
	public void testDailyStatsExcludeAdhocRuns() {
		LocalDate day = LocalDate.now().minusDays(45);

		transaction(tx -> {
			User user = dummyUser();
			PipelineRun seed = createElement(user, 4718);
			pipelineRunDao().store(seed);
			createRunOn(user, seed.getPipelineUuid(), day, PipelineRunStatus.SUCCESS, 1, 0, 0);

			PipelineRun adhoc = pipelineRunDao().createAdhocRun(user.getUuid(), new JsonObject());
			adhoc.setStatus(PipelineRunStatus.SUCCESS);
			adhoc.setSuccessCount(7);
			adhoc.setStarted(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
			pipelineRunDao().store(adhoc);
		});

		// /pipelines/runs/stats describes scheduled processing. A chat agent probing assets is not
		// that, and letting it into the chart makes the throughput numbers meaningless.
		PipelineRunDayStats bucket = bucket(pipelineRunDao().loadDailyStats(day.atStartOfDay()), day).orElseThrow();
		assertEquals(1, bucket.getRunCount(), "An ad-hoc run must not be counted in the pipeline run stats");
		assertEquals(1, bucket.getSuccessCount());
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