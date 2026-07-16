package io.metaloom.loom.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.health.HealthCheckResponse;

public class HealthEndpointIntegrationTest extends AbstractIntegrationTest {

	@Test
	public void testHealthEndpoint() throws Exception {
		// 1. Start server
		Loom server = loomServer();
		server.run(false);

		// 2. Use the REST client to call health endpoint
		try (LoomHttpClient client = httpClient(server)) {
			// Health endpoint should be accessible without authentication
			HealthCheckResponse response = client.health()
				.sync()
				.body();

			assertNotNull(response);
			assertEquals("UP", response.getStatus());
			assertNotNull(response.getVersion());
			assertNotNull(response.getTimestamp());
			assertEquals("UP", response.getDatabase());
		}
		server.shutdown();
	}

	@Test
	public void testHealthEndpointWithAuth() throws Exception {
		// 1. Start server
		Loom server = loomServer();
		server.run(false);

		// 2. Use the REST client with authentication
		try (LoomHttpClient client = httpClient(server)) {
			loginAdmin(client);
			HealthCheckResponse response = client.health()
				.sync()
				.body();

			assertNotNull(response);
			assertEquals("UP", response.getStatus());
			assertNotNull(response.getVersion());
			assertNotNull(response.getTimestamp());
			assertEquals("UP", response.getDatabase());
		}
		server.shutdown();
	}
}