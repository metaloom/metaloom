package io.metaloom.cortex.node.guard;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The OpenAI-compatible backend seam, with log probabilities.
 *
 * <p>
 * The node cannot use the shared {@code LlmInvoker}/{@code LLMProvider} plumbing that {@code llm},
 * {@code translate} and {@code filter} share, and it is worth being explicit about why: that
 * interface returns text or JSON, and a guard model's answer is not its text — it is the probability
 * of its decision token. All three families document their score that way. Reading the generated
 * word alone throws the confidence away and leaves the node's {@code threshold} option with nothing
 * to threshold.
 * </p>
 *
 * <h2>Two endpoints</h2>
 *
 * <ul>
 * <li><strong>Text → {@code POST {base}/completions}.</strong> The prompt is sent exactly as the
 * dialect rendered it, with no chat template in the way. Both llama.cpp and vLLM serve this route,
 * and it is the only way to get ShieldGemma's {@code guideline} and Granite Guardian's
 * {@code guardian_config} into the prompt without depending on a backend forwarding extra chat
 * template keyword arguments.</li>
 * <li><strong>Image → {@code POST {base}/chat/completions}.</strong> Only the chat route accepts an
 * {@code image_url} content part, so here the backend's own chat template applies and the dialect
 * renders a bare instruction. In practice this means vLLM: llama.cpp cannot serve either multimodal
 * guard model today, because no {@code mmproj} projector has been published for Llama Guard 4 and
 * {@code shieldgemma-2-4b} has no GGUF conversion at all.</li>
 * </ul>
 *
 * <p>
 * HTTP/1.1 is forced, as in every other sidecar client in the tree — the JDK client's HTTP/2 upgrade
 * is rejected by several of the servers this talks to.
 * </p>
 */
public class GuardClient {

	/**
	 * A guard call generates two to twenty-four tokens, so it is fast; the budget is for a cold
	 * backend still loading weights rather than for generation.
	 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

	/**
	 * How many alternatives per position the backend is asked for. Two would do for the decision
	 * itself, but the decision is not always at position 0, and a position holding a newline plus
	 * punctuation can push the real alternative out of a top-2 list.
	 */
	private static final int TOP_LOGPROBS = 5;

	private final String baseUrl;

	private final String apiKey;

	private final HttpClient http;

	/**
	 * @param baseUrl OpenAI-compatible base URL, with or without the trailing {@code /v1}
	 * @param apiKey  bearer token, or null/blank when the endpoint needs no auth (the usual case for
	 *                a local llama.cpp or vLLM)
	 */
	public GuardClient(String baseUrl, String apiKey) {
		this.baseUrl = normalize(baseUrl);
		this.apiKey = apiKey;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	/**
	 * Ask the model about text.
	 *
	 * @param probe the rendered probe
	 * @param model the model id to select on the endpoint
	 * @return the answer and its token probabilities
	 * @throws IOException          on transport failure or a non-200 response
	 * @throws InterruptedException when the calling thread is interrupted
	 */
	public GuardCompletion complete(GuardProbe probe, String model) throws IOException, InterruptedException {
		JsonObject body = new JsonObject()
			.put("model", model)
			.put("prompt", probe.prompt())
			.put("max_tokens", probe.maxTokens())
			// A classifier must be deterministic: the same asset has to reach the same verdict on a
			// re-run, or the cache and the ledger disagree with each other.
			.put("temperature", 0)
			.put("logprobs", TOP_LOGPROBS);

		JsonObject choice = post(baseUrl + "/completions", body);
		return new GuardCompletion(text(choice.getString("text")), tokenProbs(choice.getJsonObject("logprobs")));
	}

	/**
	 * Ask the model about an image.
	 *
	 * @param probe the rendered probe; its prompt becomes the text content part
	 * @param model the model id to select on the endpoint
	 * @param image the image to moderate, already downscaled
	 * @return the answer and its token probabilities
	 * @throws IOException          on transport failure or a non-200 response
	 * @throws InterruptedException when the calling thread is interrupted
	 */
	public GuardCompletion complete(GuardProbe probe, String model, BufferedImage image) throws IOException, InterruptedException {
		JsonArray content = new JsonArray()
			.add(new JsonObject().put("type", "text").put("text", probe.prompt()))
			.add(new JsonObject()
				.put("type", "image_url")
				.put("image_url", new JsonObject().put("url", GuardImages.toJpegDataUri(image))));

		JsonObject body = new JsonObject()
			.put("model", model)
			.put("max_tokens", probe.maxTokens())
			.put("temperature", 0)
			.put("logprobs", true)
			.put("top_logprobs", TOP_LOGPROBS)
			.put("messages", new JsonArray().add(new JsonObject().put("role", "user").put("content", content)));

		JsonObject choice = post(baseUrl + "/chat/completions", body);
		JsonObject message = choice.getJsonObject("message");
		String answer = message == null ? "" : message.getString("content");
		return new GuardCompletion(text(answer), chatTokenProbs(choice.getJsonObject("logprobs")));
	}

	/** The base URL the client was built with, so a failure message can name it. */
	public String baseUrl() {
		return baseUrl;
	}

	private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
		URI uri = URI.create(url);
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.encode()));
		if (apiKey != null && !apiKey.isBlank()) {
			rb.header("Authorization", "Bearer " + apiKey);
		}

		HttpResponse<String> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Guard endpoint " + uri + " returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
		}
		return firstChoice(response.body(), uri);
	}

	static JsonObject firstChoice(String responseBody, URI uri) throws IOException {
		JsonObject json;
		try {
			json = new JsonObject(responseBody);
		} catch (RuntimeException e) {
			throw new IOException("Guard endpoint " + uri + " returned a non-JSON body: " + truncate(responseBody), e);
		}
		JsonArray choices = json.getJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IOException("No choices in the guard response from " + uri + ": " + truncate(responseBody));
		}
		return choices.getJsonObject(0);
	}

	/**
	 * Read whichever of the two log-probability shapes the backend used on the completions route.
	 *
	 * <p>
	 * Both are needed, and finding that out cost a live check rather than a reading of either spec.
	 * The OpenAI text-completions format puts {@code logprobs.top_logprobs} at the top level as an
	 * array of {@code {token: logprob}} maps, which is what vLLM returns. <strong>llama.cpp answers
	 * the same route in the newer chat shape</strong> — {@code logprobs.content[].top_logprobs[]} of
	 * {@code {token, logprob}} objects — so a parser written to the documented text format finds
	 * nothing there, reports no probabilities, and silently degrades every verdict on the shipped
	 * sidecar to a 1/0 argmax. Nothing fails; the threshold just quietly stops meaning anything.
	 * </p>
	 *
	 * @param logprobs the {@code logprobs} object of the first choice, or null
	 * @return one map per generated position, empty when the backend reported nothing
	 */
	static List<Map<String, Double>> tokenProbs(JsonObject logprobs) {
		List<Map<String, Double>> legacy = legacyTokenProbs(logprobs);
		return legacy.isEmpty() ? chatTokenProbs(logprobs) : legacy;
	}

	/**
	 * The OpenAI text-completions shape: {@code logprobs.top_logprobs} is an array of
	 * {@code {token: logprob}} objects, one per generated position. What vLLM returns.
	 *
	 * <p>
	 * Missing entirely when the backend does not implement the parameter. That is not an error here —
	 * {@link GuardScoring} degrades to an argmax and marks the verdict inexact, which is far more
	 * useful than refusing to classify.
	 * </p>
	 */
	static List<Map<String, Double>> legacyTokenProbs(JsonObject logprobs) {
		if (logprobs == null) {
			return List.of();
		}
		JsonArray positions = logprobs.getJsonArray("top_logprobs");
		if (positions == null) {
			return List.of();
		}
		List<Map<String, Double>> result = new ArrayList<>();
		for (int i = 0; i < positions.size(); i++) {
			JsonObject position = positions.getJsonObject(i);
			if (position == null) {
				continue;
			}
			Map<String, Double> probs = new LinkedHashMap<>();
			for (String token : position.fieldNames()) {
				Double logprob = asDouble(position.getValue(token));
				if (logprob != null) {
					probs.put(token, Math.exp(logprob));
				}
			}
			result.add(probs);
		}
		return List.copyOf(result);
	}

	/**
	 * The chat completions shape: {@code logprobs.content} is an array of per-position objects, each
	 * carrying its own {@code top_logprobs} array of {@code {token, logprob}} pairs.
	 */
	static List<Map<String, Double>> chatTokenProbs(JsonObject logprobs) {
		if (logprobs == null) {
			return List.of();
		}
		JsonArray positions = logprobs.getJsonArray("content");
		if (positions == null) {
			return List.of();
		}
		List<Map<String, Double>> result = new ArrayList<>();
		for (int i = 0; i < positions.size(); i++) {
			JsonObject position = positions.getJsonObject(i);
			if (position == null) {
				continue;
			}
			Map<String, Double> probs = new LinkedHashMap<>();
			JsonArray alternatives = position.getJsonArray("top_logprobs");
			if (alternatives != null) {
				for (int j = 0; j < alternatives.size(); j++) {
					JsonObject alternative = alternatives.getJsonObject(j);
					Double logprob = alternative == null ? null : asDouble(alternative.getValue("logprob"));
					if (logprob != null) {
						probs.put(alternative.getString("token"), Math.exp(logprob));
					}
				}
			}
			// Some backends report the chosen token only at the top level. Without this a single
			// alternative position would look empty and the search would walk past the decision.
			Double chosen = asDouble(position.getValue("logprob"));
			if (chosen != null) {
				probs.putIfAbsent(position.getString("token"), Math.exp(chosen));
			}
			result.add(probs);
		}
		return List.copyOf(result);
	}

	private static Double asDouble(Object value) {
		return value instanceof Number number ? number.doubleValue() : null;
	}

	private static String text(String value) {
		return value == null ? "" : value.strip();
	}

	/**
	 * Accept either {@code http://host:port} or {@code http://host:port/v1}, because
	 * {@code AbstractLlmNodeOptions} documents the second form while every other client in the tree
	 * takes the first.
	 */
	private static String normalize(String baseUrl) {
		String url = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
		return url.endsWith("/v1") ? url : url + "/v1";
	}

	private static String truncate(String s) {
		if (s == null) {
			return "null";
		}
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}
}
