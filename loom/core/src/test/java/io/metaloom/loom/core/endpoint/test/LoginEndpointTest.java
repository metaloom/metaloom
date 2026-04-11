package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.client.common.LoomClientResponse;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

public class LoginEndpointTest extends AbstractEndpointTest {

	@Test
	public void testBasics() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse response = client.login("admin", "finger").sync().body();
			System.out.println("Got Token: " + response.getToken());
			client.setToken(response.getToken());
			UserResponse response2 = client.loadUser(ADMIN_UUID).sync().body();
			assertEquals("admin", response2.getUsername());
			assertNotNull(response2);
		}
	}

	@Test
	public void testLoginSetsCookie() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			// 1. Login and verify Set-Cookie header is present with correct attributes
			LoomClientResponse<AuthLoginResponse> loginResponse = client.login("admin", "finger").sync();
			assertEquals(200, loginResponse.statusCode());

			List<String> cookies = loginResponse.headers("Set-Cookie");
			String tokenCookie = cookies.stream()
				.filter(c -> c.startsWith(AuthenticationOptions.TOKEN_COOKIE_KEY + "="))
				.findFirst()
				.orElse(null);
			assertNotNull(tokenCookie, "Login response must set the " + AuthenticationOptions.TOKEN_COOKIE_KEY + " cookie");
			assertTrue(tokenCookie.toLowerCase().contains("httponly"), "Token cookie must be HttpOnly");
			assertTrue(tokenCookie.contains("Path=/"), "Token cookie must have Path=/");

			// Extract the token value
			String cookieValue = tokenCookie.split(";")[0];
			String tokenValue = cookieValue.split("=", 2)[1];
			assertNotNull(tokenValue);
			assertTrue(tokenValue.length() > 10, "Token value should be a JWT");

			// 2. Make an authenticated request and verify a refreshed cookie is returned
			client.setToken(loginResponse.body().getToken());

			// Small delay to ensure the refreshed token has a different iat
			Thread.sleep(1100);

			LoomClientResponse<UserResponse> userResponse = client.loadUser(ADMIN_UUID).sync();
			assertEquals(200, userResponse.statusCode());
			assertEquals("admin", userResponse.body().getUsername());

			List<String> refreshedCookies = userResponse.headers("Set-Cookie");
			String refreshedTokenCookie = refreshedCookies.stream()
				.filter(c -> c.startsWith(AuthenticationOptions.TOKEN_COOKIE_KEY + "="))
				.findFirst()
				.orElse(null);
			assertNotNull(refreshedTokenCookie, "Authenticated request must refresh the token cookie");
			assertTrue(refreshedTokenCookie.toLowerCase().contains("httponly"), "Refreshed cookie must be HttpOnly");

			String refreshedValue = refreshedTokenCookie.split(";")[0].split("=", 2)[1];
			assertNotEquals(tokenValue, refreshedValue, "Refreshed token should differ from the original");
		}
	}
}
