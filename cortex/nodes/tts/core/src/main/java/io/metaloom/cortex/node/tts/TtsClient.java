package io.metaloom.cortex.node.tts;

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
 * Minimal Java-only client for the FastAPI {@code /v1/tts} sidecar. Posts
 * {@code {text, lang, voice, format}} and returns the raw WAV bytes.
 *
 * <p>
 * Mirrors {@code SmolVLMClient}: it forces HTTP/1.1 because FastAPI rejects the
 * JDK client's default HTTP/2 upgrade attempt.
 * </p>
 */
public class TtsClient {

	private final String host;
	private final int port;

	public TtsClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/**
	 * Synthesize {@code text} in {@code lang} with {@code voice} and return the WAV bytes.
	 *
	 * @param text  the text to speak
	 * @param lang  the language routing hint ({@code de} -> Orpheus/Kartoffel, {@code en} -> Kokoro)
	 * @param voice the voice id understood by the selected engine
	 * @return the synthesized audio as WAV bytes
	 */
	public byte[] synthesize(String text, String lang, String voice) {
		JsonObject json = new JsonObject()
			.put("text", text)
			.put("lang", lang)
			.put("voice", voice)
			.put("format", "wav");

		URI uri = URI.create("http://" + host + ":" + port + "/v1/tts");
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofSeconds(120))
			.header("Content-Type", "application/json")
			.header("Accept", "audio/*")
			.POST(HttpRequest.BodyPublishers.ofString(json.encode(), StandardCharsets.UTF_8))
			.build();

		// Force HTTP/1.1 - FastAPI rejects the default HTTP/2 upgrade attempt.
		HttpClient client = HttpClient.newBuilder()
			.version(Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

		try {
			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() / 100 != 2) {
				String body = new String(response.body(), StandardCharsets.UTF_8);
				throw new RuntimeException("TTS request failed (HTTP " + response.statusCode() + "): " + body);
			}
			return response.body();
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("TTS request to " + uri + " failed", e);
		}
	}
}
