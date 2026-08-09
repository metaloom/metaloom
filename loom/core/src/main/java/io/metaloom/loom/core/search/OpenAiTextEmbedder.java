package io.metaloom.loom.core.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.TextEmbedder;
import io.metaloom.loom.api.search.VectorSpace;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link TextEmbedder} against any host that speaks the OpenAI {@code POST /embeddings} shape.
 *
 * <p>
 * <b>Why this protocol rather than a bespoke sidecar.</b> {@code sidecars/llamacpp} already runs an official llama.cpp server image and the {@code llm}
 * node already talks to it over exactly this API. Started with {@code --embeddings} and a GGUF embedding model it serves {@code /v1/embeddings} too, so
 * the whole inference host for text semantic search is a second container of an image the repository already uses - no new Python sidecar, no venv, no
 * code of ours in the request path. Anything else that speaks the same shape (Ollama, TEI, OpenAI itself) drops in unchanged.
 * </p>
 *
 * <p>
 * <b>Vectors are unit-normalized here, once.</b> With normalized vectors cosine and inner product rank identically, so the index's choice of similarity
 * function stops being something the ranking silently depends on. The rows are stamped {@code normalized = true} to record it, which is what makes the
 * assumption auditable rather than folklore.
 * </p>
 *
 * <p>
 * Every failure path is loud. A host that answers with the wrong number of vectors, or vectors of the wrong length, throws instead of returning
 * something shorter than asked for - a silently dropped document is a permanent hole in the index that nothing would ever report.
 * </p>
 */
public class OpenAiTextEmbedder implements TextEmbedder {

	private static final Logger log = LoggerFactory.getLogger(OpenAiTextEmbedder.class);

	public static final String NAME = "openai-compatible";

	private final String url;
	private final String model;
	private final String apiKey;
	private final int maxChars;
	private final Duration timeout;
	private final VectorSpace space;
	private final HttpClient http;

	public OpenAiTextEmbedder(SearchOptions options) {
		String base = options.getEmbedUrl() == null ? "" : options.getEmbedUrl().trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		// Accept both ".../v1" and ".../v1/embeddings" so an operator who pasted the full endpoint is not
		// punished with a 404 that looks like the model is missing.
		this.url = base.endsWith("/embeddings") ? base : base + "/embeddings";
		this.model = options.getEmbedModel() == null ? "" : options.getEmbedModel().trim();
		this.apiKey = options.getEmbedApiKey() == null ? "" : options.getEmbedApiKey().trim();
		this.maxChars = options.getEmbedMaxChars();
		this.timeout = Duration.ofMillis(options.getEmbedTimeoutMs());
		this.space = new VectorSpace(options.getVectorType(), this.model, options.getEmbedDimensions());
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(5_000, options.getEmbedTimeoutMs()))).build();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean isAvailable() {
		if (url.isBlank() || model.isBlank()) {
			return false;
		}
		try {
			// One real embedding is the only honest probe: a reachable host that has no embedding model
			// loaded answers /health perfectly well and then fails every actual call.
			return embed("ping").length == space.dimensions();
		} catch (Exception e) {
			log.debug("The embedding host at {} is not usable: {}", url, e.getMessage());
			return false;
		}
	}

	@Override
	public VectorSpace space() {
		return space;
	}

	@Override
	public float[] embed(String text) {
		List<float[]> vectors = embedAll(List.of(text == null ? "" : text));
		return vectors.get(0);
	}

	@Override
	public List<float[]> embedAll(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}
		JsonArray input = new JsonArray();
		for (String text : texts) {
			input.add(truncate(text));
		}
		JsonObject payload = new JsonObject().put("model", model).put("input", input);

		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(payload.encode(), StandardCharsets.UTF_8));
		if (!apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}

		HttpResponse<String> response;
		try {
			response = http.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while embedding text", e);
		} catch (Exception e) {
			throw new IllegalStateException("The embedding host at " + url + " could not be reached: " + e.getMessage(), e);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("The embedding host at " + url + " answered " + response.statusCode() + ": " + tail(response.body()));
		}
		return parse(response.body(), texts.size());
	}

	// ---------------------------------------------------------------------------------------------

	private List<float[]> parse(String body, int expected) {
		JsonArray data;
		try {
			data = new JsonObject(body).getJsonArray("data");
		} catch (Exception e) {
			throw new IllegalStateException("The embedding host at " + url + " answered with something that is not JSON: " + tail(body), e);
		}
		if (data == null || data.size() != expected) {
			throw new IllegalStateException("The embedding host at " + url + " returned " + (data == null ? "no" : String.valueOf(data.size()))
				+ " vectors for " + expected + " input(s).");
		}
		List<float[]> out = new ArrayList<>(expected);
		for (int i = 0; i < data.size(); i++) {
			JsonArray raw = data.getJsonObject(i).getJsonArray("embedding");
			if (raw == null || raw.size() != space.dimensions()) {
				throw new IllegalStateException("The embedding host at " + url + " returned a " + (raw == null ? "missing" : raw.size() + "-component")
					+ " vector, but LOOM_SEARCH_EMBED_DIMENSIONS says " + space.dimensions() + ". Fix the dimension rather than the vector: a wrong "
					+ "value here mixes incomparable vectors into one index segment.");
			}
			float[] vector = new float[raw.size()];
			for (int c = 0; c < raw.size(); c++) {
				vector[c] = ((Number) raw.getValue(c)).floatValue();
			}
			out.add(normalize(vector));
		}
		return out;
	}

	/**
	 * Scale to unit length in place.
	 *
	 * <p>
	 * A zero vector is left alone rather than divided by zero. It carries no direction and so cannot be ranked meaningfully, but it is a legitimate
	 * answer for empty input and must not become {@code NaN}, which would corrupt every comparison in the segment rather than just its own.
	 * </p>
	 */
	static float[] normalize(float[] vector) {
		double sum = 0;
		for (float component : vector) {
			sum += (double) component * component;
		}
		double length = Math.sqrt(sum);
		if (length == 0 || Double.isNaN(length)) {
			return vector;
		}
		for (int i = 0; i < vector.length; i++) {
			vector[i] = (float) (vector[i] / length);
		}
		return vector;
	}

	private String truncate(String text) {
		String value = text == null ? "" : text;
		return value.length() <= maxChars ? value : value.substring(0, maxChars);
	}

	private static String tail(String body) {
		if (body == null) {
			return "";
		}
		return body.length() <= 500 ? body : body.substring(0, 500) + "…";
	}
}
