package io.metaloom.loom.test.integration.node.docs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Is a service answering? Used to decide whether a recipe can run before it is constructed. */
public final class Probe {

	private Probe() {
	}

	/**
	 * Answering, not merely bound.
	 *
	 * <p>
	 * A TCP connect says a port is open, which a half-started sidecar also satisfies — and then the
	 * node fails deep inside an HTTP client with a message about a stream, rather than the generator
	 * saying up front that the service is not ready. Any status counts: a 404 from a server that is
	 * up is still a server that is up.
	 * </p>
	 */
	public static boolean answering(String url) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMillis(1200))
				.GET()
				.build();
			client.send(request, HttpResponse.BodyHandlers.discarding());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Answering <em>and</em> saying something in particular.
	 *
	 * <p>
	 * "A server is up on 8000" is not always the question. The three vision nodes are satisfied by
	 * any OpenAI-compatible endpoint, including a text-only one, which will happily describe a
	 * photograph it was never shown — so their requirement asks the server what it has loaded and
	 * looks for {@code multimodal} in the answer. A wrong-but-running service has to read as
	 * unavailable, or the fixture it produces is fiction with a real HTTP status code.
	 * </p>
	 */
	public static boolean bodyContains(String url, String needle) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMillis(2000))
				.GET()
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() < 400 && response.body() != null && response.body().contains(needle);
		} catch (Exception e) {
			return false;
		}
	}
}
