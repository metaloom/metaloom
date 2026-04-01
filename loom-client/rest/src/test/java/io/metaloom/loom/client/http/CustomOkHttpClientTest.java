package io.metaloom.loom.client.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.user.UserResponse;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class CustomOkHttpClientTest extends AbstractContainerTest {

	@Test
	public void testCustomClient() throws Exception {

		int port = loom.httpPort();
		String host = loom.getHost();

		AtomicReference<HttpUrl> interceptedUrl = new AtomicReference<>();
		OkHttpClient customClient = createCustomOkClient(interceptedUrl);

		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname(host)
			.setOkHttpClient(customClient)
			.setPort(port)
			.build()) {

			// Create a collection
			UserResponse userResponse = client.loadUser(USER_UUID).sync();
			assertNotNull(userResponse);
		}
	}

	@Test
	public void testConflictingBuildParams() {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setOkHttpClient(createCustomOkClient(null))
			.setConnectTimeout(Duration.ofMinutes(1))
			.setPort(123)
			.build()) {
		}
	}

	private OkHttpClient createCustomOkClient(AtomicReference<HttpUrl> interceptedUrl) {
		okhttp3.OkHttpClient.Builder builder = new OkHttpClient.Builder();
		builder.addInterceptor(chain -> {
			Request request = chain.request();
			interceptedUrl.set(request.url());
			return chain.proceed(request);
		});
		return builder.build();
	}
}
