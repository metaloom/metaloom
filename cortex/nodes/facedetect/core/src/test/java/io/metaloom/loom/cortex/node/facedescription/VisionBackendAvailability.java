package io.metaloom.loom.cortex.node.facedescription;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.node.facedescription.FacedescriptionNode;

/**
 * Guard for the tests that need the local vision model backing {@code FacedescriptionNode}.
 *
 * <p>
 * {@code FacedescriptionNode#processFace} asks an OpenAI-compatible vision backend for a JSON
 * description and returns {@code null} after three failed attempts, so without an endpoint the test
 * failed on a {@code NullPointerException} — which reads exactly like a real regression rather than
 * a missing prerequisite. Same pattern as {@code SmolVLMAvailability} in the captioning node and
 * {@code TestEnv} in the llm node.
 * </p>
 *
 * <p>
 * <strong>A socket connect is not enough.</strong> This guard used to accept any listener on the
 * port and the test failed anyway on a machine where an unrelated service — a
 * {@code gaussian-splatting} viewer — happened to hold {@code 127.0.0.1:8080}. The TCP handshake
 * succeeded, the assumption passed, all three model calls failed, and the resulting NPE was
 * indistinguishable from the regression this guard exists to rule out. So the probe now asks the
 * endpoint to prove it speaks the protocol the node uses: {@code GET <baseUrl>/models} must answer
 * {@code 200} with an OpenAI-shaped model list. Anything else is "not the backend", not "the backend
 * is broken".
 * </p>
 *
 * <p>
 * The base URL is read from {@link FacedescriptionNode#URL} rather than restated here, so the guard
 * cannot drift away from the endpoint the node actually calls.
 * </p>
 */
public final class VisionBackendAvailability {

	private static final Logger log = LoggerFactory.getLogger(VisionBackendAvailability.class);

	/** The OpenAI-compatible base URL, e.g. {@code http://127.0.0.1:8080/v1}. */
	public static final String BASE_URL = System.getProperty("loom.test.vision.url", FacedescriptionNode.URL);

	private static final int CONNECT_TIMEOUT_MS = 500;

	/** Long enough for a loaded {@code llama-server} to answer a metadata request, short enough not to stall a skip. */
	private static final int PROBE_TIMEOUT_MS = 2_000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private VisionBackendAvailability() {
	}

	/**
	 * Skip the calling test unless an OpenAI-compatible vision backend answers at {@link #BASE_URL}.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(),
			"Skipping - no OpenAI-compatible vision endpoint at " + BASE_URL
				+ " (a listener on that port is not enough; it must serve GET /models)");
	}

	public static boolean isRunning() {
		return isListening() && servesOpenAiModels();
	}

	/**
	 * A cheap pre-check so the common case — nothing running at all — skips immediately instead of
	 * waiting out the HTTP timeout.
	 */
	private static boolean isListening() {
		// Inside the try: a malformed -Dloom.test.vision.url must skip the test, not error it. An
		// unusable override is still "no backend here".
		try (Socket socket = new Socket()) {
			URI uri = URI.create(BASE_URL);
			int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
			socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			log.debug("Nothing listening for the vision endpoint at {}", BASE_URL, e);
			return false;
		}
	}

	/**
	 * Whether {@code GET <baseUrl>/models} answers with an OpenAI model list.
	 *
	 * <p>
	 * This is what separates "the vision backend is up" from "some other process owns the port". The
	 * served model ids are logged, because the next failure mode after a foreign service is the right
	 * server with the wrong (non-vision) model loaded — and that one the log has to explain, since the
	 * test can only see a null description.
	 * </p>
	 */
	private static boolean servesOpenAiModels() {
		String url = BASE_URL.endsWith("/") ? BASE_URL + "models" : BASE_URL + "/models";
		try (HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
			.build()) {

			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
				.GET()
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.debug("{} answered {}, so it is not the vision backend", url, response.statusCode());
				return false;
			}

			JsonNode data = MAPPER.readTree(response.body()).path("data");
			if (!data.isArray() || data.isEmpty()) {
				log.debug("{} answered 200 but not with an OpenAI model list: {}", url, response.body());
				return false;
			}
			log.info("Vision backend at {} serves {}", BASE_URL, modelIds(data));
			return true;
		} catch (Exception e) {
			log.debug("Could not probe {} for an OpenAI model list", url, e);
			return false;
		}
	}

	private static List<String> modelIds(JsonNode data) {
		return java.util.stream.StreamSupport.stream(data.spliterator(), false)
			.map(model -> model.path("id").asText("<unnamed>"))
			.toList();
	}
}
