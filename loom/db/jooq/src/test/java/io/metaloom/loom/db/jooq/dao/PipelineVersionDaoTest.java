package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class PipelineVersionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineVersionDao, PipelineVersion> {

	@Override
	public PipelineVersion createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setMeta(new JsonObject().put("key", "value"));
		pipelineDao().store(pipeline);

		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			i + 1,
			"version_" + i,
			"Test version " + i,
			new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()),
			true,
			i,
			false,
			new JsonObject().put("versionKey", "versionValue")
		);
		return version;
	}

	@Override
	public void assertCreate(PipelineVersion createdElement) {
		assertEquals("version_0", createdElement.getName());
		assertEquals("Test version 0", createdElement.getDescription());
		assertNotNull(createdElement.getDefinition());
		assertEquals(true, createdElement.isEnabled());
		assertEquals(0, createdElement.getPriority());
		assertEquals(false, createdElement.isDryRun());
		assertNotNull(createdElement.getMeta());
		assertEquals(1, createdElement.getVersionNumber());
		assertNotNull(createdElement.getPipelineUuid());
	}

	// ── The lookups the CRUD harness does not reach ───────────────────────
	//
	// Everything above is the generic create/read/update/delete harness. The four methods below are
	// the ones the REST layer actually resolves a pipeline through, and none of them was exercised:
	// a version history is ordered data, and the ordering is the part that breaks.

	/**
	 * "Latest" is the highest version <em>number</em>, not the most recently inserted row. Falling
	 * back to insertion order would serve a stale graph the moment versions are ever stored out of
	 * order - a restore, a backfill, or two authors saving concurrently.
	 */
	@Test
	public void testLoadLatestByPipelineOrdersByVersionNumberNotInsertionOrder() {
		User user = dummyUser();
		UUID pipelineUuid = storePipeline(user, "latest_by_number");

		storeVersion(user, pipelineUuid, 1);
		PipelineVersion v3 = storeVersion(user, pipelineUuid, 3);
		storeVersion(user, pipelineUuid, 2);

		PipelineVersion latest = pipelineVersionDao().loadLatestByPipeline(pipelineUuid);

		assertNotNull(latest);
		assertEquals(3, latest.getVersionNumber());
		assertEquals(v3.getUuid(), latest.getUuid());
	}

	@Test
	public void testLoadLatestByPipelineIsScopedToItsPipeline() {
		User user = dummyUser();
		UUID mine = storePipeline(user, "mine");
		UUID theirs = storePipeline(user, "theirs");

		storeVersion(user, mine, 1);
		storeVersion(user, theirs, 9);

		assertEquals(1, pipelineVersionDao().loadLatestByPipeline(mine).getVersionNumber(),
			"Another pipeline's higher version number must not leak across");
		assertNull(pipelineVersionDao().loadLatestByPipeline(UUID.randomUUID()),
			"An unknown pipeline has no latest version");
	}

	/**
	 * Loading a specific version is what makes a pipeline run reproducible: a run records the version
	 * it executed, and re-reading it must give back that graph rather than whatever is current.
	 */
	@Test
	public void testLoadByPipelineAndVersionPinsOneVersion() {
		User user = dummyUser();
		UUID pipelineUuid = storePipeline(user, "by_version");

		PipelineVersion v1 = storeVersion(user, pipelineUuid, 1);
		PipelineVersion v2 = storeVersion(user, pipelineUuid, 2);

		assertEquals(v1.getUuid(), pipelineVersionDao().loadByPipelineAndVersion(pipelineUuid, 1).getUuid());
		assertEquals(v2.getUuid(), pipelineVersionDao().loadByPipelineAndVersion(pipelineUuid, 2).getUuid());
		assertEquals("v1", pipelineVersionDao().loadByPipelineAndVersion(pipelineUuid, 1).getName());
	}

	@Test
	public void testLoadByPipelineAndVersionIsNullRatherThanWrongForAMiss() {
		User user = dummyUser();
		UUID pipelineUuid = storePipeline(user, "missing_version");
		UUID otherUuid = storePipeline(user, "other_pipeline");
		storeVersion(user, pipelineUuid, 1);
		storeVersion(user, otherUuid, 7);

		assertNull(pipelineVersionDao().loadByPipelineAndVersion(pipelineUuid, 2),
			"A version that does not exist must not resolve to the nearest one");
		assertNull(pipelineVersionDao().loadByPipelineAndVersion(pipelineUuid, 7),
			"The version number is only unique per pipeline; number 7 belongs to another pipeline");
	}

	/**
	 * The bulk lookup exists so listing N pipelines resolves N latest-versions in one query instead
	 * of N. Its contract is deliberately forgiving - unknown uuids are skipped rather than being an
	 * error - because a list can race a concurrent delete.
	 */
	@Test
	public void testLoadByUuidsResolvesManyVersionsInOneGo() {
		User user = dummyUser();
		UUID first = storePipeline(user, "bulk_a");
		UUID second = storePipeline(user, "bulk_b");

		PipelineVersion a = storeVersion(user, first, 1);
		PipelineVersion b = storeVersion(user, second, 1);
		storeVersion(user, second, 2);

		List<PipelineVersion> loaded = pipelineVersionDao().loadByUuids(List.of(a.getUuid(), b.getUuid()));

		assertEquals(Set.of(a.getUuid(), b.getUuid()),
			loaded.stream().map(PipelineVersion::getUuid).collect(Collectors.toSet()),
			"Exactly the requested versions - not every version of the pipelines they belong to");
	}

	@Test
	public void testLoadByUuidsSkipsWhatItCannotFindAndShortCircuitsOnNothing() {
		User user = dummyUser();
		UUID pipelineUuid = storePipeline(user, "bulk_partial");
		PipelineVersion known = storeVersion(user, pipelineUuid, 1);

		List<PipelineVersion> loaded = pipelineVersionDao().loadByUuids(List.of(known.getUuid(), UUID.randomUUID()));
		assertEquals(1, loaded.size(), "An unknown uuid is skipped, not an error");
		assertEquals(known.getUuid(), loaded.get(0).getUuid());

		// No query at all for an empty request: an `in ()` predicate is not portable and the answer
		// is known without asking.
		assertEquals(List.of(), pipelineVersionDao().loadByUuids(List.of()));
		assertEquals(List.of(), pipelineVersionDao().loadByUuids(null));
	}

	/**
	 * The full history, oldest first. The order is the contract - a version list rendered newest-first
	 * by accident reads as though the pipeline was authored backwards.
	 */
	@Test
	public void testLoadByPipelineReturnsTheWholeHistoryInVersionOrder() {
		User user = dummyUser();
		UUID pipelineUuid = storePipeline(user, "history");

		storeVersion(user, pipelineUuid, 2);
		storeVersion(user, pipelineUuid, 1);
		storeVersion(user, pipelineUuid, 3);

		assertEquals(List.of(1, 2, 3), pipelineVersionDao().loadByPipeline(pipelineUuid).stream()
			.map(PipelineVersion::getVersionNumber)
			.collect(Collectors.toList()));
	}

	private UUID storePipeline(User user, String name) {
		Pipeline pipeline = pipelineDao().createPipeline(user, name);
		pipelineDao().store(pipeline);
		return pipeline.getUuid();
	}

	private PipelineVersion storeVersion(User user, UUID pipelineUuid, int versionNumber) {
		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipelineUuid,
			versionNumber,
			"v" + versionNumber,
			"Test version " + versionNumber,
			new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()),
			true,
			0,
			false,
			null);
		pipelineVersionDao().store(version);
		return version;
	}

	@Override
	public PipelineVersionDao getDao() {
		return pipelineVersionDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public void updateElement(PipelineVersion element) {
		element.setName("updated-version");
		element.setDescription("Updated description");
		element.setPriority(99);
		element.setEnabled(false);
	}

	@Override
	public void assertUpdate(PipelineVersion updatedElement) {
		assertEquals("updated-version", updatedElement.getName());
		assertEquals("Updated description", updatedElement.getDescription());
		assertEquals(99, updatedElement.getPriority());
		assertEquals(false, updatedElement.isEnabled());
	}

}