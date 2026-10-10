package io.metaloom.cortex.node.voiceprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.vertx.core.json.JsonObject;

/**
 * Minimal Java-only client for the FastAPI {@code /v1/voiceprints} sidecar ({@code sidecars/voiceprint}). Uploads an audio file as a multipart
 * form and returns the parsed {@link VoiceprintResult}.
 *
 * <p>
 * Mirrors {@code SentimentClient}: it forces HTTP/1.1 because FastAPI rejects the JDK client's default HTTP/2 upgrade attempt. The multipart body is
 * assembled by hand - the JDK client has no multipart support - and streamed from the file, so a long recording never sits in the heap twice.
 * </p>
 *
 * <p>
 * This class is the seam the tests replace - the Dagger module provides one instance, and both the unit test and the integration test hand the node
 * a stub or a client pointed at a mock server instead of a real model.
 * </p>
 */
public class VoiceprintClient {

	/** Wall clock for one analysis when the node's {@code timeoutMs} is 0. A ten-hour recording at 4000 windows takes well under this on a GPU. */
	public static final long DEFAULT_TIMEOUT_MS = 600_000;

	private final String host;
	private final int port;

	/**
	 * One client for the lifetime of this instance. Building a new {@link HttpClient} per call started a fresh selector thread and connection pool for
	 * every item and never closed them. HTTP/1.1 is forced because FastAPI rejects the JDK client's HTTP/2 upgrade attempt.
	 */
	private final HttpClient client = HttpClient.newBuilder()
		.version(Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	public VoiceprintClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/**
	 * The form fields of one request. Every value is optional: null leaves the sidecar's default in effect.
	 */
	public record Params(String model, Double windowSeconds, Double hopSeconds, Double minSpeechSeconds, Double clusterThreshold,
		Integer maxSpeakers) {

		public static Params defaults() {
			return new Params(null, null, null, null, null, null);
		}

		Map<String, String> fields() {
			Map<String, String> out = new LinkedHashMap<>();
			if (model != null && !model.isBlank()) {
				out.put("model", model);
			}
			if (windowSeconds != null) {
				out.put("window_s", Double.toString(windowSeconds));
			}
			if (hopSeconds != null) {
				out.put("hop_s", Double.toString(hopSeconds));
			}
			if (minSpeechSeconds != null) {
				out.put("min_speech_s", Double.toString(minSpeechSeconds));
			}
			if (clusterThreshold != null) {
				out.put("cluster_threshold", Double.toString(clusterThreshold));
			}
			if (maxSpeakers != null) {
				out.put("max_speakers", Integer.toString(maxSpeakers));
			}
			return out;
		}
	}

	/**
	 * Analyse {@code audio} and return the speakers, windows and pooled voiceprint the sidecar found.
	 *
	 * @param audio     a decodable audio file (WAV, FLAC, OGG, MP3; the node hands over a 16 kHz WAV for video)
	 * @param params    the request fields
	 * @param timeoutMs wall clock for the whole request; 0 uses {@link #DEFAULT_TIMEOUT_MS}
	 */
	public VoiceprintResult analyze(Path audio, Params params, long timeoutMs) {
		String boundary = "----metaloom-" + UUID.randomUUID();
		URI uri = URI.create("http://" + host + ":" + port + "/v1/voiceprints");
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Accept", "application/json")
				.POST(multipart(boundary, params.fields(), audio))
				.build();
		} catch (IOException e) {
			throw new RuntimeException("Could not read " + audio + " for the voiceprint request", e);
		}
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() / 100 != 2) {
				throw new RuntimeException("Voiceprint request failed (HTTP " + response.statusCode() + "): " + response.body());
			}
			return VoiceprintResult.fromJson(new JsonObject(response.body()));
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("Voiceprint request to " + uri + " failed", e);
		}
	}

	/**
	 * A multipart/form-data body: the text fields first, then the file streamed from disk, then the closing boundary.
	 */
	static HttpRequest.BodyPublisher multipart(String boundary, Map<String, String> fields, Path file) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		for (Map.Entry<String, String> field : fields.entrySet()) {
			head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
		}
		String name = file.getFileName() == null ? "audio" : file.getFileName().toString().replace("\"", "");
		head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
			+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		if (!Files.isReadable(file)) {
			throw new IOException("not readable: " + file);
		}
		return HttpRequest.BodyPublishers.concat(
			HttpRequest.BodyPublishers.ofByteArray(head.toByteArray()),
			HttpRequest.BodyPublishers.ofFile(file),
			HttpRequest.BodyPublishers.ofByteArray(tail));
	}
}
