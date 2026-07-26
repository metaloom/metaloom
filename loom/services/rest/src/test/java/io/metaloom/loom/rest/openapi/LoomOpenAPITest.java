package io.metaloom.loom.rest.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;

public class LoomOpenAPITest {

	private static OpenAPI api;

	@BeforeAll
	public static void generate() {
		api = new LoomOpenAPI().generate();
	}

	@Test
	public void testGenerate() throws JsonProcessingException {
		assertFalse(new LoomOpenAPI().generateJson().isBlank());
		assertFalse(new LoomOpenAPI().generateYaml().isBlank());
	}

	@Test
	public void testCoversAllEndpoints() {
		// A regression here means an endpoint stopped being registered in the spec - the
		// generator constructs every endpoint of this module by hand.
		assertTrue(api.getPaths().size() > 100, "Expected the spec to cover all endpoints but found " + api.getPaths().size() + " paths");
		for (String path : List.of("/api/v1/users/{uuid}", "/api/v1/assets/{uuid}/binary", "/api/v1/pipelines/{uuid}/run",
			"/api/v1/skills/library", "/api/v1/processors", "/api/v1/health")) {
			assertNotNull(api.getPaths().get(path), "Missing path " + path);
		}
	}

	@Test
	public void testUsesPathTemplating() {
		for (String path : api.getPaths().keySet()) {
			assertFalse(path.contains(":"), "Path " + path + " still uses the Vert.x parameter syntax");
		}
	}

	@Test
	public void testDocumentsPathParameters() {
		PathItem item = api.getPaths().get("/api/v1/users/{uuid}");
		List<Parameter> parameters = item.getParameters();
		assertNotNull(parameters, "Path parameters are not documented");
		assertEquals(1, parameters.size());
		assertEquals("uuid", parameters.get(0).getName());
		assertEquals("path", parameters.get(0).getIn());
		assertTrue(parameters.get(0).getRequired());
	}

	@Test
	public void testDescribesOperations() {
		Operation load = api.getPaths().get("/api/v1/users/{uuid}").getGet();
		assertEquals("Load a user", load.getDescription());
		assertEquals("Load a user", load.getSummary());
		assertEquals("getUsersByUuid", load.getOperationId());
		assertEquals(List.of("users"), load.getTags());
	}

	@Test
	public void testSecurity() {
		assertNotNull(api.getComponents().getSecuritySchemes().get("bearerAuth"));
		assertNotNull(api.getComponents().getSecuritySchemes().get("cookieAuth"));
		assertEquals(2, api.getSecurity().size(), "The API should be documented as secured by default");
		// Pre-auth routes have to opt out or a spec viewer would demand a token for login.
		assertTrue(api.getPaths().get("/api/v1/login").getPost().getSecurity().isEmpty());
	}

	@Test
	public void testInlinesJsonExamples() {
		Object example = api.getPaths().get("/api/v1/users/{uuid}").getGet()
			.getResponses().get("200").getContent().get("application/json").getExample();
		assertFalse(example instanceof String, "Examples must be inlined as JSON, not as encoded strings");
	}
}
