package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineValidateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineValidationError;
import io.metaloom.loom.rest.model.pipeline.PipelineValidationResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code POST /api/v1/pipelines/validate}.
 *
 * <p>
 * The route exists so a draft can be checked without being stored, and so the author is told
 * <em>everything</em> that is wrong with it rather than one thing per round trip. Both halves are
 * asserted here: that a rejected definition leaves no pipeline behind, and that a definition with
 * several independent mistakes comes back with several errors.
 * </p>
 *
 * <p>
 * A rejected definition is a <b>200 with {@code valid: false}</b>, not a 400 — the caller asked a
 * question and got an answer. The 400 belongs to create and update, which are covered elsewhere.
 * </p>
 */
public class PipelineValidateEndpointTest extends AbstractEndpointTest {

	/** A valid two-node graph: a filesystem source feeding a hash. */
	private static JsonObject definition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	private PipelineValidationResponse validate(LoomHttpClient client, JsonObject definition) throws LoomClientException {
		return client.validatePipeline(new PipelineValidateRequest().setDefinition(definition)).sync().body();
	}

	private static List<String> codes(PipelineValidationResponse response) {
		return response.getErrors().stream().map(PipelineValidationError::getCode).toList();
	}

	// ── The happy path ───────────────────────────────────────────────────

	@Test
	@DisplayName("A sound definition validates and reports no errors")
	void testValidDefinition() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineValidationResponse response = validate(client, definition());

			assertTrue(response.isValid(), "Expected the definition to be accepted: " + response.getErrors());
			assertThat(response.getErrors()).isEmpty();
		}
	}

	/**
	 * Warnings are not errors, and the distinction is the reason the route can be used from the
	 * editor at all: no worker is online in this test, and refusing on that basis would make saving
	 * a pipeline depend on which machines happen to be up.
	 */
	@Test
	@DisplayName("A kind no online worker offers is a warning, not an error")
	void testOfflineWorkerIsOnlyAWarning() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineValidationResponse response = validate(client, definition());

			assertTrue(response.isValid());
			assertThat(response.getWarnings()).isNotEmpty();
			assertTrue(response.getWarnings().get(0).contains("No online worker"), response.getWarnings().toString());
		}
	}

	@Test
	@DisplayName("Validating stores nothing")
	void testValidationDoesNotPersist() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			long before = client.listPipelines().sync().body().getMetainfo().getTotalCount();

			assertTrue(validate(client, definition()).isValid());
			validate(client, new JsonObject().put("nodes", new JsonArray()));

			assertEquals(before, client.listPipelines().sync().body().getMetainfo().getTotalCount(),
				"Neither a valid nor an invalid draft may leave a pipeline behind");
		}
	}

	// ── Rejection, in full ───────────────────────────────────────────────

	@Test
	@DisplayName("Every problem comes back, not just the first")
	void testAllErrorsAreReported() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject broken = new JsonObject()
				.put("nodes", new JsonArray()
					.add(new JsonObject().put("id", "Not Valid").put("type", "filesystem-source"))
					.add(new JsonObject().put("id", "pn2").put("type", "no-such-node-kind"))
					.add(new JsonObject().put("id", "pn3")));

			PipelineValidationResponse response = validate(client, broken);

			assertFalse(response.isValid());
			assertEquals(List.of("NODE_ID_INVALID", "NODE_TYPE_UNKNOWN", "NODE_TYPE_MISSING"), codes(response),
				"Three independent mistakes must produce three errors: " + response.getErrors());
			assertEquals(List.of("Not Valid", "pn2", "pn3"),
				response.getErrors().stream().map(PipelineValidationError::getNodeId).toList(),
				"Each error must name the node it belongs to so the editor can mark it");
		}
	}

	@Test
	@DisplayName("Both dangling edge endpoints are reported")
	void testAllEdgeErrorsAreReported() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject broken = definition();
			broken.getJsonArray("edges")
				.add(new JsonObject().put("source", "ghost").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media"))
				.add(new JsonObject().put("source", "pn1").put("sourcePort", "media")
					.put("target", "phantom").put("targetPort", "media"));

			PipelineValidationResponse response = validate(client, broken);

			assertFalse(response.isValid());
			assertEquals(List.of("EDGE_SOURCE_UNKNOWN", "EDGE_TARGET_UNKNOWN"), codes(response), response.getErrors().toString());
			assertEquals("ghost->pn2", response.getErrors().get(0).getEdgeId());
		}
	}

	@Test
	@DisplayName("A cycle is reported")
	void testCycleIsReported() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject broken = definition();
			broken.getJsonArray("edges").add(new JsonObject().put("source", "pn2").put("sourcePort", "media")
				.put("target", "pn1").put("targetPort", "media"));

			PipelineValidationResponse response = validate(client, broken);

			assertFalse(response.isValid());
			assertThat(codes(response)).contains("CYCLE");
		}
	}

	@Test
	@DisplayName("An empty graph is rejected")
	void testEmptyGraphIsRejected() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineValidationResponse response = validate(client, new JsonObject().put("nodes", new JsonArray()));

			assertFalse(response.isValid());
			assertEquals(List.of("EMPTY_GRAPH"), codes(response));
		}
	}

	/**
	 * The route and the create path must agree, or validating a draft is worthless: a definition
	 * this says is valid has to save, and one it refuses has to be refused.
	 */
	@Test
	@DisplayName("A validated definition is one that saves, and a rejected one is one that does not")
	void testAgreesWithTheCreatePath() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			assertTrue(validate(client, definition()).isValid());
			client.createPipeline(new PipelineCreateRequest()
				.setName("validated-" + UUID.randomUUID())
				.setDefinition(definition())).sync().body();

			JsonObject broken = definition();
			broken.getJsonArray("nodes").getJsonObject(1).put("type", "no-such-node-kind");
			assertFalse(validate(client, broken).isValid());
			expect(400, "Bad Request", client.createPipeline(new PipelineCreateRequest()
				.setName("rejected-" + UUID.randomUUID())
				.setDefinition(broken)));
		}
	}

	// ── Request and permission handling ──────────────────────────────────

	@Test
	@DisplayName("A request without a definition is a 400")
	void testMissingDefinitionIsABadRequest() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.validatePipeline(new PipelineValidateRequest()));
		}
	}

	@Test
	@DisplayName("A caller without CREATE_PIPELINE is 403")
	void testWithoutPermissionIsForbidden() throws Exception {
		try (LoomHttpClient nobody = loginPermissionlessClient()) {
			expect(403, "Forbidden", nobody.validatePipeline(new PipelineValidateRequest().setDefinition(definition())));
		}
	}

	/**
	 * Read access is deliberately not enough. Validating a draft is an authoring action, and the
	 * reply describes the caller's own definition rather than anything stored — so
	 * {@code READ_PIPELINE} neither grants it nor needs to.
	 */
	@Test
	@DisplayName("READ_PIPELINE alone is not enough; CREATE_PIPELINE is")
	void testCreatePipelineIsTheGate() throws Exception {
		try (LoomHttpClient reader = loginClientWith("pipeline-reader", Permission.READ_PIPELINE)) {
			expect(403, "Forbidden", reader.validatePipeline(new PipelineValidateRequest().setDefinition(definition())));
		}
		try (LoomHttpClient author = loginClientWith("pipeline-author", Permission.CREATE_PIPELINE)) {
			assertTrue(validate(author, definition()).isValid());
		}
	}

	/**
	 * {@code /validate} is a literal path registered before the {@code :uuid} wildcard. Registered
	 * the other way round, "validate" is read as a pipeline uuid and this answers a 400 about a
	 * malformed uuid instead.
	 */
	@Test
	@DisplayName("The literal /validate route is not shadowed by the :uuid wildcard")
	void testRouteIsNotShadowedByTheUuidWildcard() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			assertTrue(validate(client, definition()).isValid(),
				"A 400 here means the wildcard route won and read \"validate\" as a uuid");
		}
	}
}
