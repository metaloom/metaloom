package io.metaloom.loom.client.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.user.UserResponse;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

@Disabled("Needs a Loom server image that this repository does not build - see AbstractContainerTest")
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
			UserResponse userResponse = client.loadUser(USER_UUID).sync().body();
			assertNotNull(userResponse);
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
