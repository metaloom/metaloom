package io.metaloom.cortex.node.videocaptioning;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import io.metaloom.video4j.utils.ImageUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Minimal OpenAI-compatible chat-completions client for vision-language models. It speaks the same {@code POST /v1/chat/completions} protocol served by
 * both vLLM and llama.cpp's {@code llama-server --mmproj}, so a single client drives every backend in the comparison - the model is selected by id and the
 * endpoint by base URL.
 *
 * <p>Two request shapes are supported:
 * <ul>
 * <li>{@link #captionFrames(List, String)} - a single user turn carrying the prompt plus N sampled frames as {@code image_url} data-URI parts. Works on
 * every backend (llama.cpp and vLLM), and keeps frame selection on the Java side.</li>
 * <li>{@link #captionVideoUrl(String, String)} - a single user turn carrying a {@code video_url} part pointing at a local file. Only vLLM decodes this
 * natively (Qwen2.5-VL temporal path); llama.cpp does not.</li>
 * </ul>
 */
public class VideoVLMClient {

	private final String baseUrl;
	private final String model;
	private final String apiKey;
	private final int maxTokens;
	private final double temperature;
	private final HttpClient http;

	public VideoVLMClient(String baseUrl, String model, String apiKey, int maxTokens, double temperature) {
		// Normalise: allow either "http://host:port" or a full ".../v1/chat/completions".
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.model = model;
		this.apiKey = apiKey;
		this.maxTokens = maxTokens;
		this.temperature = temperature;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	/** Caption a set of sampled frames in a single multi-image prompt. */
	public CaptionResult captionFrames(List<BufferedImage> frames, String prompt) throws IOException, InterruptedException {
		JsonArray content = new JsonArray();
		content.add(new JsonObject().put("type", "text").put("text", prompt));
		for (BufferedImage frame : frames) {
			String dataUri = "data:image/jpeg;base64," + ImageUtils.toBase64JPG(frame);
			content.add(new JsonObject()
				.put("type", "image_url")
				.put("image_url", new JsonObject().put("url", dataUri)));
		}
		return send(content, frames.size());
	}

	/** Caption a whole video file via a native {@code video_url} part (vLLM only). */
	public CaptionResult captionVideoUrl(String fileUri, String prompt) throws IOException, InterruptedException {
		JsonArray content = new JsonArray();
		content.add(new JsonObject().put("type", "text").put("text", prompt));
		content.add(new JsonObject()
			.put("type", "video_url")
			.put("video_url", new JsonObject().put("url", fileUri)));
		return send(content, 1);
	}

	private CaptionResult send(JsonArray userContent, int inputCount) throws IOException, InterruptedException {
		JsonObject message = new JsonObject().put("role", "user").put("content", userContent);
		JsonObject body = new JsonObject()
			.put("model", model)
			.put("max_tokens", maxTokens)
			.put("temperature", temperature)
			.put("messages", new JsonArray().add(message));

		URI uri = URI.create(baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/v1/chat/completions");
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofMinutes(10))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.encode()));
		if (apiKey != null && !apiKey.isBlank()) {
			rb.header("Authorization", "Bearer " + apiKey);
		}

		long start = System.currentTimeMillis();
		HttpResponse<String> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
		long latency = System.currentTimeMillis() - start;

		if (response.statusCode() != 200) {
			throw new IOException("VLM endpoint " + uri + " returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
		}
		String text = extractContent(response.body());
		return new CaptionResult(text, latency, inputCount);
	}

	/** Pull {@code choices[0].message.content} out of an OpenAI chat-completions response. */
	static String extractContent(String responseBody) {
		JsonObject json = new JsonObject(responseBody);
		JsonArray choices = json.getJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IllegalStateException("No choices in response: " + truncate(responseBody));
		}
		JsonObject msg = choices.getJsonObject(0).getJsonObject("message");
		String content = msg == null ? null : msg.getString("content");
		return content == null ? "" : content.trim();
	}

	private static String truncate(String s) {
		if (s == null) {
			return "null";
		}
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}

	public String model() {
		return model;
	}

	public String baseUrl() {
		return baseUrl;
	}
}
