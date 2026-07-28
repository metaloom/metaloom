package io.metaloom.cortex.node.depthmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vertx.core.json.JsonObject;

/**
 * Minimal Java-only client for the FastAPI {@code /v1/depth} sidecar ({@code sidecars/depth}). Posts the source image as base64 and returns the
 * sidecar's normalized result.
 *
 * <p>
 * Mirrors {@code SentimentClient}: it forces HTTP/1.1 because FastAPI rejects the JDK client's default HTTP/2 upgrade attempt.
 * </p>
 *
 * <p>
 * This class is the seam the tests replace - the Dagger module provides one instance, and both the unit test and the integration test hand the node a
 * stub instead of a real model. Neither the class nor {@link #depth} is final, so subclassing works.
 * </p>
 */
public class DepthmapClient {

	private final String host;
	private final int port;
	private final int timeoutMs;

	public DepthmapClient(String host, int port, int timeoutMs) {
		this.host = host;
		this.port = port;
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Estimate depth for {@code image} and return the sidecar's normalized result:
	 * {@code {model, convention, source, width, height, png_b64, stats{p05,p50,p95}, metric?{min_m,max_m}}}.
	 *
	 * <p>
	 * The returned {@code png_b64} is a 16-bit grayscale PNG following the {@code NEARNESS} convention - {@code 65535} is nearest to the camera. The
	 * {@code width}/{@code height} describe the returned <em>map</em>, which is the source image downscaled to {@code maxDim}, not the source image
	 * itself.
	 * </p>
	 *
	 * @param image         the source image
	 * @param mode          which model family to run
	 * @param modelOverride an explicit checkpoint, or null to use the sidecar's own default for the mode
	 * @param maxDim        longest side to send; the sidecar clamps this to its own cap
	 * @return the depth result
	 * @throws IOException when the image cannot be encoded
	 */
	public JsonObject depth(BufferedImage image, DepthMode mode, String modelOverride, int maxDim) throws IOException {
		JsonObject json = new JsonObject()
			.put("image_b64", DepthImages.toBase64Png(image))
			.put("mode", (mode == null ? DepthMode.RELATIVE : mode).name())
			.put("max_dim", maxDim);
		if (modelOverride != null && !modelOverride.isBlank()) {
			json.put("model", modelOverride);
		}

		URI uri = URI.create("http://" + host + ":" + port + "/v1/depth");
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofMillis(timeoutMs))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.encode(), StandardCharsets.UTF_8))
			.build();

		// Force HTTP/1.1 - FastAPI rejects the default HTTP/2 upgrade attempt.
		HttpClient client = HttpClient.newBuilder()
			.version(Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() / 100 != 2) {
				throw new RuntimeException("Depth request failed (HTTP " + response.statusCode() + "): " + response.body());
			}
			return new JsonObject(response.body());
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("Depth request to " + uri + " failed", e);
		}
	}
}
