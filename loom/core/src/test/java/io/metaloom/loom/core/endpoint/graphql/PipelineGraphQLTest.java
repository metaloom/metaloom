package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code Pipeline}, {@code PipelineVersion} and {@code PipelineRun} domain elements. The fixture does not provision pipelines,
 * so each test seeds its own via the DAO layer of the booted server.
 */
public class PipelineGraphQLTest extends AbstractGraphQLTest {

	/**
	 * Seed a pipeline with a single version (wired as its latest) and one run. Returns the pipeline uuid.
	 */
	private UUID seedPipeline() {
		UUID adminUuid = adminUuid();

		Pipeline pipeline = daos().pipelineDao().createPipeline(adminUuid, "graphql-test-pipeline");
		daos().pipelineDao().store(pipeline);

		PipelineVersion version = daos().pipelineVersionDao().createVersion(adminUuid, pipeline.getUuid(), 1,
			"v1", "The first version", new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()), true, 5, false,
			new JsonObject().put("author", "test"));
		daos().pipelineVersionDao().store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		daos().pipelineDao().update(pipeline);

		PipelineRun run = daos().pipelineRunDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus(PipelineRunStatus.SUCCESS);
		run.setMediaCount(3);
		run.setSuccessCount(3);
		daos().pipelineRunDao().store(run);

		return pipeline.getUuid();
	}

	@Test
	public void testPipelineByUuidWithLatestVersion() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			JsonObject variables = new JsonObject().put("uuid", pipelineUuid.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { pipeline(uuid: $uuid) { uuid latestVersionUuid meta latestVersion { uuid versionNumber name enabled priority definition } } }",
				variables);

			Map<String, Object> pipeline = object(data, "pipeline");
			assertNotNull(pipeline);
			assertEquals(pipelineUuid.toString(), pipeline.get("uuid"));

			Map<String, Object> latest = object(pipeline, "latestVersion");
			assertNotNull(latest, "The latest version back reference should resolve");
			assertEquals(1, latest.get("versionNumber"));
			assertEquals("v1", latest.get("name"));
			assertEquals(Boolean.TRUE, latest.get("enabled"));
			assertEquals(5, latest.get("priority"));
			assertNotNull(latest.get("definition"), "The Json definition should be serialized");
		}
	}

	@Test
	public void testPipelineList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			Map<String, Object> data = data(client, "{ pipelines { uuid } }");
			List<Map<String, Object>> pipelines = list(data, "pipelines");
			assertTrue(pipelines.stream().anyMatch(p -> pipelineUuid.toString().equals(p.get("uuid"))),
				"The seeded pipeline should be listed");
		}
	}

	@Test
	public void testPipelineVersions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			JsonObject variables = new JsonObject().put("pipelineUuid", pipelineUuid.toString());
			Map<String, Object> data = data(client,
				"query($pipelineUuid: ID!) { pipelineVersions(pipelineUuid: $pipelineUuid) { uuid versionNumber pipeline { uuid } } }",
				variables);

			List<Map<String, Object>> versions = list(data, "pipelineVersions");
			assertEquals(1, versions.size());
			assertEquals(1, versions.get(0).get("versionNumber"));
			// The back reference resolves to the owning pipeline.
			assertEquals(pipelineUuid.toString(), object(versions.get(0), "pipeline").get("uuid"));
		}
	}

	@Test
	public void testPipelineVersionByNumber() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			JsonObject variables = new JsonObject().put("pipelineUuid", pipelineUuid.toString()).put("versionNumber", 1);
			Map<String, Object> data = data(client,
				"query($pipelineUuid: ID!, $versionNumber: Int!) { pipelineVersionByNumber(pipelineUuid: $pipelineUuid, versionNumber: $versionNumber) { versionNumber name } }",
				variables);

			Map<String, Object> version = object(data, "pipelineVersionByNumber");
			assertNotNull(version);
			assertEquals("v1", version.get("name"));
		}
	}

	@Test
	public void testPipelineRuns() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			JsonObject variables = new JsonObject().put("pipelineUuid", pipelineUuid.toString());
			Map<String, Object> data = data(client,
				"query($pipelineUuid: ID) { pipelineRuns(pipelineUuid: $pipelineUuid) { uuid status successCount pipeline { uuid } } }",
				variables);

			List<Map<String, Object>> runs = list(data, "pipelineRuns");
			assertEquals(1, runs.size());
			assertEquals("SUCCESS", runs.get(0).get("status"));
			assertEquals(3, runs.get(0).get("successCount"));
			assertEquals(pipelineUuid.toString(), object(runs.get(0), "pipeline").get("uuid"));
		}
	}

	@Test
	public void testPipelineRunsByStatus() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seedPipeline();

			JsonObject variables = new JsonObject().put("status", "SUCCESS");
			Map<String, Object> data = data(client,
				"query($status: String) { pipelineRuns(status: $status) { uuid status } }", variables);

			List<Map<String, Object>> runs = list(data, "pipelineRuns");
			assertTrue(runs.size() >= 1, "At least the seeded SUCCESS run should match");
			assertTrue(runs.stream().allMatch(r -> "SUCCESS".equals(r.get("status"))), "Only SUCCESS runs should be returned");
		}
	}

	@Test
	public void testLatestPipelineRun() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineUuid = seedPipeline();

			JsonObject variables = new JsonObject().put("pipelineUuid", pipelineUuid.toString());
			Map<String, Object> data = data(client,
				"query($pipelineUuid: ID!) { latestPipelineRun(pipelineUuid: $pipelineUuid) { uuid status } }", variables);

			Map<String, Object> run = object(data, "latestPipelineRun");
			assertNotNull(run);
			assertEquals("SUCCESS", run.get("status"));
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_PIPELINE, "{ pipeline(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_VERSION, "{ pipelineVersion(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_VERSION,
				"{ pipelineVersionByNumber(pipelineUuid: \"" + uuid + "\", versionNumber: 1) { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_RUN, "{ pipelineRun(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_RUN, "{ latestPipelineRun(pipelineUuid: \"" + uuid + "\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_PIPELINE, "{ pipelines { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_VERSION, "{ pipelineVersions(pipelineUuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_PIPELINE_RUN, "{ pipelineRuns { uuid } }");
		}
	}
}
