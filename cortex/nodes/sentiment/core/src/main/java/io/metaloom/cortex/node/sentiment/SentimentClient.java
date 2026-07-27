package io.metaloom.cortex.node.sentiment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Minimal Java-only client for the FastAPI {@code /v1/sentiment} sidecar
 * ({@code sidecars/sentiment}). Posts {@code {texts:[...], lang}} and returns the
 * scored result for the single text it sent.
 *
 * <p>
 * Mirrors {@code TtsClient}: it forces HTTP/1.1 because FastAPI rejects the JDK
 * client's default HTTP/2 upgrade attempt.
 * </p>
 *
 * <p>
 * This class is the seam the tests replace - the Dagger module provides one
 * instance, and both the unit test and the integration test hand the node a stub
 * or a client pointed at a mock server instead of a real model.
 * </p>
 */
public class SentimentClient {

	private final String host;
	private final int port;

	public SentimentClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/**
	 * Score {@code text} and return the sidecar's normalized result:
	 * {@code {label, score, polarity, scores{positive,neutral,negative}, lang, model, chunks, truncated}}.
	 *
	 * @param text          the text to analyse
	 * @param lang          the language routing hint ({@code de}, {@code en}, or {@code auto} to let the sidecar detect it)
	 * @param modelOverride per-language checkpoint overrides, or null to use the sidecar's own defaults
	 * @return the scored result
	 */
	public JsonObject analyze(String text, String lang, JsonObject modelOverride) {
		JsonObject json = new JsonObject()
			.put("texts", new JsonArray().add(text))
			.put("lang", lang);
		if (modelOverride != null && !modelOverride.isEmpty()) {
			json.put("models", modelOverride);
		}

		URI uri = URI.create("http://" + host + ":" + port + "/v1/sentiment");
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofSeconds(120))
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
				throw new RuntimeException("Sentiment request failed (HTTP " + response.statusCode() + "): " + response.body());
			}
			JsonArray results = new JsonArray(response.body());
			if (results.isEmpty()) {
				throw new RuntimeException("Sentiment sidecar returned no result for the submitted text");
			}
			return results.getJsonObject(0);
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("Sentiment request to " + uri + " failed", e);
		}
	}
}
