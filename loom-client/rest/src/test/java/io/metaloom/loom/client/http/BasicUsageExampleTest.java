package io.metaloom.loom.client.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.user.UserResponse;

public class BasicUsageExampleTest extends AbstractContainerTest {

	@Test
	public void testExample() throws Exception {

		int port = loom.httpPort();
		String host = loom.getHost();

		try (LoomClient client = LoomHttpClient.builder()
			.setHostname(host)
			.setPort(port)
			.build()) {

			UserResponse user = client.loadUser(USER_UUID).sync();
			assertNotNull(user);
		}
	}
}
