package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

public class LoginEndpointTest extends AbstractEndpointTest {

	@Test
	public void testBasics() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse response = client.login("admin", "finger").sync();
			System.out.println("Got Token: " + response.getToken());
			client.setToken(response.getToken());
			UserResponse response2 = client.loadUser(ADMIN_UUID).sync();
			assertEquals("admin", response2.getUsername());
			assertNotNull(response2);
		}
	}
}
