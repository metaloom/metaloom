package io.metaloom.loom.client.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.impl.LoomHttpClientImpl;
import okhttp3.OkHttpClient;

/**
 * Builder level tests. No server is involved - these only cover how {@link LoomHttpClient#builder()} handles its own parameters.
 */
public class LoomHttpClientBuilderTest {

	/**
	 * The timeout setters own the client they configure, so they refuse to run once the caller has supplied their own {@link OkHttpClient} - the
	 * timeout would silently be dropped otherwise.
	 */
	@Test
	public void testConflictingBuildParams() {
		LoomHttpClientImpl.Builder builder = LoomHttpClient.builder()
			.setHostname("localhost")
			.setPort(123)
			.setOkHttpClient(new OkHttpClient.Builder().build());

		RuntimeException error = assertThrows(RuntimeException.class, () -> builder.setConnectTimeout(Duration.ofMinutes(1)));
		assertEquals("Please configure the timeout on the okHttpClient you provided.", error.getMessage());

		assertThrows(RuntimeException.class, () -> builder.setReadTimeout(Duration.ofMinutes(1)));
		assertThrows(RuntimeException.class, () -> builder.setWriteTimeout(Duration.ofMinutes(1)));
	}

	@Test
	public void testCustomClientIsAccepted() {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setPort(123)
			.setOkHttpClient(new OkHttpClient.Builder().build())
			.build()) {
			assertNotNull(client);
		}
	}
}
