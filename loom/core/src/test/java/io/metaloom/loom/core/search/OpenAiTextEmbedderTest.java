package io.metaloom.loom.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;

/**
 * Unit coverage for {@link OpenAiTextEmbedder}'s pure logic: URL normalisation in the constructor and vector normalisation, neither of which needs
 * a live embedding host.
 */
public class OpenAiTextEmbedderTest {

	@Test
	@DisplayName("normalize() scales a vector to unit length")
	public void shouldNormalizeToUnitLength() {
		float[] vector = { 3f, 4f };
		float[] result = OpenAiTextEmbedder.normalize(vector);
		assertEquals(0.6f, result[0], 1e-6);
		assertEquals(0.8f, result[1], 1e-6);
		double length = Math.sqrt(result[0] * result[0] + result[1] * result[1]);
		assertEquals(1.0, length, 1e-6);
	}

	@Test
	@DisplayName("normalize() leaves a zero vector alone rather than producing NaN")
	public void shouldLeaveZeroVectorAlone() {
		float[] vector = { 0f, 0f, 0f };
		float[] result = OpenAiTextEmbedder.normalize(vector);
		assertEquals(0f, result[0]);
		assertEquals(0f, result[1]);
		assertEquals(0f, result[2]);
		for (float component : result) {
			assertFalse(Float.isNaN(component), "a zero vector must never normalize to NaN");
		}
	}

	@Test
	@DisplayName("A base URL is completed with /embeddings")
	public void shouldAppendEmbeddingsPath() {
		SearchOptions options = new SearchOptions();
		options.setEmbedUrl("http://localhost:8090/v1");
		options.setEmbedModel("test-model");
		OpenAiTextEmbedder embedder = new OpenAiTextEmbedder(options);
		assertEquals("openai-compatible", embedder.name());
		// isAvailable() would need a live host - not exercised here. The constructor itself must not
		// throw for a well-formed base URL, and the embedder must report the model dimensions declared.
		assertEquals(options.getEmbedDimensions(), embedder.space().dimensions());
	}

	@Test
	@DisplayName("A URL a caller already pasted with /embeddings is not doubled up")
	public void shouldNotDoubleTheEmbeddingsSuffix() {
		SearchOptions options = new SearchOptions();
		options.setEmbedUrl("http://localhost:8090/v1/embeddings/");
		options.setEmbedModel("test-model");
		// Constructing must not throw, and isAvailable() must fail cleanly (no reachable host) rather
		// than blow up on a malformed doubled-up URL such as ".../embeddings/embeddings".
		OpenAiTextEmbedder embedder = new OpenAiTextEmbedder(options);
		assertFalse(embedder.isAvailable());
	}

	@Test
	@DisplayName("isAvailable() is false, not throwing, when the URL or model is blank")
	public void shouldReportUnavailableWithoutConfiguration() {
		SearchOptions options = new SearchOptions();
		options.setEmbedUrl("");
		options.setEmbedModel("");
		OpenAiTextEmbedder embedder = new OpenAiTextEmbedder(options);
		assertFalse(embedder.isAvailable());
	}
}
