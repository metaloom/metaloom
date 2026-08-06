package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies the three version routes under {@code /api/v1/pipelines/:uuid/}: {@code GET /versions},
 * {@code GET /versions/:version} and {@code POST /versions/:version/restore}.
 *
 * <p>
 * The single property the whole feature rests on is that <b>a stored version is never mutated</b>.
 * An update appends {@code latest + 1} and a restore copies an old version <em>forward</em> as a new
 * one rather than rewinding to it - which is what makes the history a history rather than a
 * changelog of the current state. Both tests below therefore assert not only what the new version
 * says but that the older ones still say what they always did.
 * </p>
 *
 * <p>
 * Until this class existed the routes were exercised only by mocked Playwright specs, which stop at
 * the fetch and never reach the server.
 * </p>
 */
public class PipelineVersionEndpointTest extends AbstractEndpointTest {

	/**
	 * A minimal valid two-node graph whose source scans {@code scanPath}.
	 *
	 * <p>
	 * The path is what tells the versions apart: each one is written with a different root, so a
	 * version that quietly picked up a later definition is visible as the wrong path rather than as
	 * an equal-looking JSON blob.
	 * </p>
	 */
	private static JsonObject definition(String scanPath) {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true)
					.put("options", new JsonObject().put("path", scanPath)))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	/** The root the version's source node scans - the marker that identifies which definition this is. */
	private static String scanPath(PipelineResponse response) {
		return response.getDefinition().getJsonArray("nodes").getJsonObject(0)
			.getJsonObject("options").getString("path");
	}

	private PipelineResponse createPipeline(LoomHttpClient client, String scanPath) throws LoomClientException {
		return client.createPipeline(new PipelineCreateRequest()
			.setName("version-test-" + UUID.randomUUID())
			.setDescription("A pipeline whose versions are under test")
			.setDefinition(definition(scanPath))).sync().body();
	}

	private PipelineResponse redefine(LoomHttpClient client, UUID uuid, String scanPath) throws LoomClientException {
		return client.updatePipeline(uuid, new PipelineUpdateRequest().setDefinition(definition(scanPath)))
			.sync().body();
	}

	// ── Versioning ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Each update appends a version and leaves the earlier ones untouched")
	void testVersionsAreAppendedAndImmutable() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineResponse v1 = createPipeline(client, "/media/v1");
			UUID uuid = v1.getUuid();
			PipelineResponse v2 = redefine(client, uuid, "/media/v2");
			PipelineResponse v3 = redefine(client, uuid, "/media/v3");

			assertEquals(1, v1.getVersionNumber(), "The first version of a created pipeline is 1");
			assertEquals(2, v2.getVersionNumber(), "An update appends latest + 1");
			assertEquals(3, v3.getVersionNumber());
			assertNotEquals(v1.getVersionUuid(), v2.getVersionUuid(), "Each version is its own row");
			assertNotEquals(v2.getVersionUuid(), v3.getVersionUuid());

			PipelineVersionListResponse versions = client.listPipelineVersions(uuid).sync().body();
			assertThat(versions.getData()).extracting(PipelineResponse::getVersionNumber)
				.as("every version stays listed")
				.containsExactlyInAnyOrder(1, 2, 3);
			assertThat(versions.getData()).extracting(PipelineResponse::getUuid)
				.as("a version renders under the pipeline it belongs to")
				.containsOnly(uuid);

			// The point of the class: an update copies forward, it does not rewrite.
			assertEquals("/media/v1", scanPath(client.loadPipelineVersion(uuid, 1).sync().body()),
				"Version 1 must still carry the definition it was created with");
			assertEquals("/media/v2", scanPath(client.loadPipelineVersion(uuid, 2).sync().body()),
				"Version 2 must still carry the definition it was updated to");
			assertEquals("/media/v3", scanPath(client.loadPipeline(uuid).sync().body()),
				"The pipeline itself renders from its latest version");
		}
	}

	@Test
	@DisplayName("Restoring an old version copies it forward as a new one and answers 201")
	void testRestoreCopiesForward() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineResponse v1 = createPipeline(client, "/media/v1");
			UUID uuid = v1.getUuid();
			redefine(client, uuid, "/media/v2");
			redefine(client, uuid, "/media/v3");

			var response = client.restorePipelineVersion(uuid, 1, new PipelineVersionRestoreRequest()).sync();
			assertEquals(201, response.statusCode(), "A restore creates a version, so it is a 201");

			PipelineResponse restored = response.body();
			assertEquals(4, restored.getVersionNumber(),
				"A restore appends latest + 1 rather than rewinding to the restored number");
			assertEquals("/media/v1", scanPath(restored), "The restored version carries version 1's definition");
			assertNotEquals(v1.getVersionUuid(), restored.getVersionUuid(), "The copy is a row of its own");

			PipelineResponse v1After = client.loadPipelineVersion(uuid, 1).sync().body();
			assertEquals(v1.getVersionUuid(), v1After.getVersionUuid(), "Version 1 must be the same row as before");
			assertEquals(1, v1After.getVersionNumber());
			assertEquals("/media/v1", scanPath(v1After), "Restoring version 1 must not rewrite version 1");

			assertThat(client.listPipelineVersions(uuid).sync().body().getData())
				.extracting(PipelineResponse::getVersionNumber)
				.as("the restore adds to the history rather than replacing it")
				.containsExactlyInAnyOrder(1, 2, 3, 4);
			assertEquals(4, client.loadPipeline(uuid).sync().body().getVersionNumber(),
				"The pipeline now renders from the restored version");
		}
	}

	@Test
	@DisplayName("Loading a version that does not exist is a 404")
	void testLoadUnknownVersionIsNotFound() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID uuid = createPipeline(client, "/media/v1").getUuid();

			expect(404, "Not Found", client.loadPipelineVersion(uuid, 99));
			expect(404, "Not Found", client.loadPipelineVersion(UUID.randomUUID(), 1));
			expect(404, "Not Found", client.restorePipelineVersion(uuid, 99, new PipelineVersionRestoreRequest()));
		}
	}

	// ── Permissions ──────────────────────────────────────────────────────

	@Test
	@DisplayName("Listing versions needs READ_PIPELINE_VERSION")
	void testListVersionsNeedsReadPipelineVersion() throws Exception {
		UUID uuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			uuid = createPipeline(admin, "/media/v1").getUuid();
		}

		try (LoomHttpClient denied = loginPermissionlessClient()) {
			expect(403, "Forbidden", denied.listPipelineVersions(uuid));
		}
		try (LoomHttpClient granted = loginClientWith("version-lister", Permission.READ_PIPELINE_VERSION)) {
			assertThat(granted.listPipelineVersions(uuid).sync().body().getData()).hasSize(1);
		}
	}

	@Test
	@DisplayName("Loading a single version needs READ_PIPELINE_VERSION")
	void testLoadVersionNeedsReadPipelineVersion() throws Exception {
		UUID uuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			uuid = createPipeline(admin, "/media/v1").getUuid();
		}

		try (LoomHttpClient denied = loginPermissionlessClient()) {
			expect(403, "Forbidden", denied.loadPipelineVersion(uuid, 1));
		}
		try (LoomHttpClient granted = loginClientWith("version-reader", Permission.READ_PIPELINE_VERSION)) {
			assertEquals("/media/v1", scanPath(granted.loadPipelineVersion(uuid, 1).sync().body()));
		}
	}

	@Test
	@DisplayName("Restoring needs RESTORE_PIPELINE_VERSION, which reading a version does not confer")
	void testRestoreNeedsRestorePipelineVersion() throws Exception {
		UUID uuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			uuid = createPipeline(admin, "/media/v1").getUuid();
			redefine(admin, uuid, "/media/v2");
		}

		// Reading the history and rewriting the pipeline from it are different acts, so the read
		// permission must not be enough to perform the write.
		try (LoomHttpClient reader = loginClientWith("version-restore-reader", Permission.READ_PIPELINE_VERSION)) {
			expect(403, "Forbidden", reader.restorePipelineVersion(uuid, 1, new PipelineVersionRestoreRequest()));
		}
		try (LoomHttpClient restorer = loginClientWith("version-restorer", Permission.RESTORE_PIPELINE_VERSION)) {
			var response = restorer.restorePipelineVersion(uuid, 1, new PipelineVersionRestoreRequest()).sync();
			assertEquals(201, response.statusCode());
			assertEquals(3, response.body().getVersionNumber());
		}
	}
}
