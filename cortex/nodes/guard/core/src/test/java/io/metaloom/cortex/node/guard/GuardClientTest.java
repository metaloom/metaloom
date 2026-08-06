package io.metaloom.cortex.node.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

/**
 * The wire formats.
 *
 * <p>
 * Two different log-probability shapes reach this node — the legacy completions one, an array of
 * {@code {token: logprob}} maps, and the chat one, an array of objects each holding their own
 * {@code top_logprobs} list — and the node picks between them by which endpoint it called. Getting
 * either wrong does not throw; it silently produces an empty probability list, which degrades every
 * verdict to the coarse argmax fallback without anything failing.
 * </p>
 */
class GuardClientTest {

	private static final URI URI_UNDER_TEST = URI.create("http://localhost:8080/v1/completions");

	@Test
	void testLegacyCompletionsLogprobsAreExponentiatedPerPosition() {
		JsonObject logprobs = new JsonObject()
			.put("top_logprobs", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("unsafe", -0.05).put("safe", -3.0))
				.add(new JsonObject().put("\n", -0.01)));

		List<Map<String, Double>> positions = GuardClient.legacyTokenProbs(logprobs);

		assertThat(positions).hasSize(2);
		assertThat(positions.get(0).get("unsafe")).isCloseTo(Math.exp(-0.05), within(1e-12));
		assertThat(positions.get(0).get("safe")).isCloseTo(Math.exp(-3.0), within(1e-12));
		assertThat(positions.get(1)).containsOnlyKeys("\n");
	}

	@Test
	void testChatCompletionsLogprobsAreReadFromTheNestedShape() {
		JsonObject logprobs = new JsonObject()
			.put("content", new io.vertx.core.json.JsonArray()
				.add(new JsonObject()
					.put("token", "Yes")
					.put("logprob", -0.2)
					.put("top_logprobs", new io.vertx.core.json.JsonArray()
						.add(new JsonObject().put("token", "Yes").put("logprob", -0.2))
						.add(new JsonObject().put("token", "No").put("logprob", -1.7)))));

		List<Map<String, Double>> positions = GuardClient.chatTokenProbs(logprobs);

		assertThat(positions).hasSize(1);
		assertThat(positions.get(0).get("Yes")).isCloseTo(Math.exp(-0.2), within(1e-12));
		assertThat(positions.get(0).get("No")).isCloseTo(Math.exp(-1.7), within(1e-12));
	}

	@Test
	void testChatShapeFallsBackToTheChosenTokenWhenNoAlternativesAreListed() {
		// Some backends report only the chosen token. Without this the position would look empty
		// and the decision search would walk straight past it.
		JsonObject logprobs = new JsonObject()
			.put("content", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("token", "No").put("logprob", -0.01)));

		assertThat(GuardClient.chatTokenProbs(logprobs).get(0).get("No")).isCloseTo(Math.exp(-0.01), within(1e-12));
	}

	@Test
	void testTheCompletionsRouteReadsBothShapes() {
		// Load-bearing, and found the hard way against a live llama.cpp. The OpenAI text-completions
		// format is the flat one below and is what vLLM returns; llama.cpp answers the same route in
		// the chat shape. A parser written to only the documented format finds nothing on the
		// shipped sidecar, reports no probabilities, and silently degrades every verdict to a 1/0
		// argmax - nothing fails, the threshold just stops meaning anything.
		JsonObject vllmShape = new JsonObject()
			.put("top_logprobs", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("Yes", -0.1).put("No", -2.0)));
		JsonObject llamaCppShape = new JsonObject()
			.put("content", new io.vertx.core.json.JsonArray()
				.add(new JsonObject()
					.put("token", "Yes")
					.put("logprob", -0.1)
					.put("top_logprobs", new io.vertx.core.json.JsonArray()
						.add(new JsonObject().put("token", "Yes").put("logprob", -0.1))
						.add(new JsonObject().put("token", "No").put("logprob", -2.0)))));

		for (JsonObject shape : List.of(vllmShape, llamaCppShape)) {
			List<Map<String, Double>> positions = GuardClient.tokenProbs(shape);
			assertThat(positions).hasSize(1);
			assertThat(positions.get(0).get("Yes")).isCloseTo(Math.exp(-0.1), within(1e-12));
			assertThat(positions.get(0).get("No")).isCloseTo(Math.exp(-2.0), within(1e-12));
		}
		assertThat(GuardClient.tokenProbs(null)).isEmpty();
	}

	@Test
	void testMissingLogprobsIsEmptyRatherThanAnError() {
		// A backend that does not implement the parameter degrades the score; it must not fail the
		// call, because the generated text is still a usable answer.
		assertThat(GuardClient.legacyTokenProbs(null)).isEmpty();
		assertThat(GuardClient.legacyTokenProbs(new JsonObject())).isEmpty();
		assertThat(GuardClient.chatTokenProbs(null)).isEmpty();
		assertThat(GuardClient.chatTokenProbs(new JsonObject())).isEmpty();
	}

	@Test
	void testAnEmptyChoicesArrayIsReportedWithTheEndpoint() {
		IOException error = assertThrows(IOException.class, () -> GuardClient.firstChoice("{\"choices\":[]}", URI_UNDER_TEST));
		assertThat(error).hasMessageContaining("localhost:8080");
	}

	@Test
	void testANonJsonBodyIsReportedRatherThanThrowingAParseError() {
		// An HTML error page from a reverse proxy in front of the backend is the usual cause, and
		// "unexpected character" would send whoever reads the log looking in the wrong place.
		IOException error = assertThrows(IOException.class, () -> GuardClient.firstChoice("<html>502 Bad Gateway</html>", URI_UNDER_TEST));
		assertThat(error).hasMessageContaining("non-JSON body");
	}

	@Test
	void testTheBaseUrlAcceptsBothConventions() {
		// AbstractLlmNodeOptions documents the /v1 form; every other client in the tree takes the
		// bare host. Both have to reach the same endpoint or the node is unconfigurable by anyone
		// who has configured a sibling node before.
		assertThat(new GuardClient("http://127.0.0.1:8080/v1", null).baseUrl()).isEqualTo("http://127.0.0.1:8080/v1");
		assertThat(new GuardClient("http://127.0.0.1:8080", null).baseUrl()).isEqualTo("http://127.0.0.1:8080/v1");
		assertThat(new GuardClient("http://127.0.0.1:8080/v1/", null).baseUrl()).isEqualTo("http://127.0.0.1:8080/v1");
	}
}
