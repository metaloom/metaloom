package io.metaloom.cortex.node.captioning;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guard for the tests that need a local SmolVLM endpoint.
 *
 * <p>
 * Without one those tests failed the build with a bare connection error, which is indistinguishable
 * from a real regression. Assuming the prerequisite skips them on machines that do not have it and
 * still runs them where it is present — the same pattern as {@code TestEnv} in
 * {@code loom/core} and {@code TestEnv} in the llm node.
 * </p>
 */
public final class SmolVLMAvailability {

	private static final Logger log = LoggerFactory.getLogger(SmolVLMAvailability.class);

	public static final String HOST = System.getProperty("loom.test.smolvlm.host", "localhost");

	public static final int PORT = Integer.getInteger("loom.test.smolvlm.port", 8000);

	private static final int CONNECT_TIMEOUT_MS = 500;

	private SmolVLMAvailability() {
	}

	/**
	 * Skip the calling test when no HTTP server answers on the SmolVLM port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(), "Skipping - no SmolVLM endpoint reachable at " + HOST + ":" + PORT);
	}

	public static boolean isRunning() {
		// A TCP connect is not enough: any unrelated process holding the port passed the old probe and the tests then
		// failed with "header parser received no bytes". Require a complete HTTP response (any status - the sidecar
		// has no health route, but a non-HTTP listener cannot produce a status line).
		HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
			.build();
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + PORT + "/"))
				.timeout(Duration.ofSeconds(2))
				.GET()
				.build();
			client.send(request, HttpResponse.BodyHandlers.discarding());
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Exception e) {
			log.debug("No SmolVLM endpoint reachable at {}:{}", HOST, PORT, e);
			return false;
		}
	}
}
