package io.metaloom.cortex.node.vlm;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Minimal OpenAI-compatible chat-completions client for single-image vision-language calls.
 *
 * <p>
 * It speaks the {@code POST /v1/chat/completions} protocol served by vLLM, llama.cpp's {@code llama-server --mmproj} and any other OpenAI-compatible
 * runtime, so one client covers every backend - the model is picked by id, the backend by base URL. The image travels as an {@code image_url} content part
 * holding a base64 JPEG data URI.
 * </p>
 *
 * <p>
 * This is the seam the tests replace: the Dagger module provides one instance, and both the unit test and the integration test hand the node a client
 * pointed at a mock server instead of a GPU.
 * </p>
 */
public class VlmChatClient {

	/** A document page can take a while to transcribe, so the per-request budget is generous. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

	private final String baseUrl;

	private final String apiKey;

	private final HttpClient http;

	/**
	 * @param baseUrl endpoint base URL, either {@code http://host:port} or a full {@code .../v1/chat/completions}
	 * @param apiKey  bearer token, or null/blank when the endpoint needs no auth (the usual case for a local vLLM)
	 */
	public VlmChatClient(String baseUrl, String apiKey) {
		this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
		this.apiKey = apiKey;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	/**
	 * Send one image plus a text prompt as a single user turn.
	 *
	 * @param image       the image to attach; already scaled to whatever the model expects
	 * @param prompt      the instruction text
	 * @param model       the model id to select on the endpoint
	 * @param maxTokens   output token limit
	 * @param temperature sampling temperature
	 * @return the reply
	 * @throws IOException          on transport failure or a non-200 response
	 * @throws InterruptedException when the calling thread is interrupted
	 */
	public VlmReply complete(BufferedImage image, String prompt, String model, int maxTokens, double temperature)
		throws IOException, InterruptedException {
		JsonArray content = new JsonArray()
			.add(new JsonObject().put("type", "text").put("text", prompt))
			.add(new JsonObject()
				.put("type", "image_url")
				.put("image_url", new JsonObject().put("url", VlmImages.toJpegDataUri(image))));

		JsonObject body = new JsonObject()
			.put("model", model)
			.put("max_tokens", maxTokens)
			.put("temperature", temperature)
			.put("messages", new JsonArray().add(new JsonObject().put("role", "user").put("content", content)));

		URI uri = URI.create(baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/v1/chat/completions");
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(REQUEST_TIMEOUT)
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
		return parse(response.body(), latency);
	}

	/**
	 * Pull {@code choices[0].message.content} and {@code choices[0].finish_reason} out of an OpenAI chat-completions response.
	 */
	static VlmReply parse(String responseBody, long latencyMs) throws IOException {
		JsonObject json;
		try {
			json = new JsonObject(responseBody);
		} catch (RuntimeException e) {
			throw new IOException("VLM endpoint returned a non-JSON body: " + truncate(responseBody), e);
		}
		JsonArray choices = json.getJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IOException("No choices in VLM response: " + truncate(responseBody));
		}
		JsonObject choice = choices.getJsonObject(0);
		JsonObject message = choice.getJsonObject("message");
		String content = message == null ? null : message.getString("content");
		return new VlmReply(content == null ? "" : content.strip(), choice.getString("finish_reason"), latencyMs);
	}

	private static String truncate(String s) {
		if (s == null) {
			return "null";
		}
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}

	public String baseUrl() {
		return baseUrl;
	}
}
