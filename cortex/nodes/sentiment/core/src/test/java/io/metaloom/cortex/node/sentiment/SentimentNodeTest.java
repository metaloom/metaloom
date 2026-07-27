package io.metaloom.cortex.node.sentiment;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link SentimentNode}. The FastAPI sidecar is replaced by a mocked {@link SentimentClient}, so no server is required.
 * Verifies the text-in / label-out flow, the ordered text-source resolution, truncation, and the in-heap skip cache.
 */
class SentimentNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final JsonObject SIDECAR_RESULT = new JsonObject()
		.put("label", "NEGATIVE")
		.put("score", 0.87d)
		.put("polarity", -0.81d)
		.put("scores", new JsonObject().put("positive", 0.06d).put("neutral", 0.07d).put("negative", 0.87d))
		.put("lang", "de")
		.put("model", "oliverguhr/german-sentiment-bert")
		.put("chunks", 1)
		.put("truncated", false);

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private SentimentClient sentimentClient;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		sentimentClient = mock(SentimentClient.class);
		when(sentimentClient.analyze(anyString(), anyString(), any())).thenReturn(SIDECAR_RESULT.copy());

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "report.pdf", "fake-document");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private SentimentNode node() {
		return node(new SentimentNodeOptions());
	}

	private SentimentNode node(SentimentNodeOptions options) {
		return new SentimentNode(null, cortexOptions, options, sentimentClient);
	}

	private NodeContext<LoomMedia> ctx(Map<String, Map<String, Object>> upstream) {
		return NodeContext.create(media, upstream);
	}

	private NodeContext<LoomMedia> ctxWithTika(String text) {
		return ctx(Map.of("tika", Map.of("tika_content", text)));
	}

	@Test
	void testScoresUpstreamText() {
		NodeResult result = node().process(ctxWithTika("Der Kundenservice war eine Katastrophe."));
		assertThat(result).isSuccess();

		assertEquals("NEGATIVE", result.get(SentimentNode.OUTPUT_SENTIMENT_LABEL));
		assertEquals(0.87d, result.get(SentimentNode.OUTPUT_SENTIMENT_SCORE));

		String json = result.get(SentimentNode.OUTPUT_SENTIMENT_RESULT);
		assertNotNull(json, "The node should emit the full result payload");
		JsonObject payload = new JsonObject(json);
		assertEquals(-0.81d, payload.getDouble("polarity"));
		assertEquals("oliverguhr/german-sentiment-bert", payload.getString("model"));
		// The payload records which upstream output the text came from.
		assertEquals("tika", payload.getJsonObject("source").getString("nodeId"));
		assertEquals("tika_content", payload.getJsonObject("source").getString("outputKey"));
		assertEquals("Der Kundenservice war eine Katastrophe.".length(), payload.getInteger("textChars"));

		// No model overrides configured, so the sidecar's own defaults are used.
		verify(sentimentClient).analyze(eq("Der Kundenservice war eine Katastrophe."), eq("auto"), isNull());
	}

	@Test
	void testSkippedWhenNoUpstreamText() {
		NodeResult result = node().process(NodeContext.create(media));
		assertThat(result).isSkipped();
	}

	@Test
	void testSkippedWhenUpstreamTextBlank() {
		NodeResult result = node().process(ctxWithTika("   "));
		assertThat(result).isSkipped();
	}

	@Test
	void testTextSourcesResolvedInOrder() {
		// tika comes before ocr in the default list, so tika wins when both are present.
		NodeResult result = node().process(ctx(Map.of(
			"ocr", Map.of("ocr_text", "from ocr"),
			"tika", Map.of("tika_content", "from tika"))));
		assertThat(result).isSuccess();

		verify(sentimentClient).analyze(eq("from tika"), anyString(), any());
	}

	@Test
	void testFallsThroughToLaterTextSource() {
		// Only ocr is present, so the tika entry is skipped and ocr supplies the text.
		NodeResult result = node().process(ctx(Map.of("ocr", Map.of("ocr_text", "from ocr"))));
		assertThat(result).isSuccess();

		verify(sentimentClient).analyze(eq("from ocr"), anyString(), any());
		JsonObject payload = new JsonObject(result.get(SentimentNode.OUTPUT_SENTIMENT_RESULT));
		assertEquals("ocr_text", payload.getJsonObject("source").getString("outputKey"));
	}

	@Test
	void testTextTruncatedToMaxChars() {
		SentimentNodeOptions options = new SentimentNodeOptions().setMaxChars(10);

		NodeResult result = node(options).process(ctxWithTika("0123456789ABCDEF"));
		assertThat(result).isSuccess();

		verify(sentimentClient).analyze(eq("0123456789"), anyString(), any());
	}

	@Test
	void testModelOverridesPassedToSidecar() {
		SentimentNodeOptions options = new SentimentNodeOptions()
			.setModelDe("scherrmann/GermanFinBert_SC_Sentiment")
			.setLanguage("de");

		assertThat(node(options).process(ctxWithTika("Die Quartalszahlen enttäuschen."))).isSuccess();

		verify(sentimentClient).analyze(anyString(), eq("de"),
			eq(new JsonObject().put("de", "scherrmann/GermanFinBert_SC_Sentiment")));
	}

	@Test
	void testCustomTextSource() {
		SentimentNodeOptions options = new SentimentNodeOptions().setTextSources(List.of("llm:llm_result_summary"));

		NodeResult result = node(options).process(ctx(Map.of("llm", Map.of("llm_result_summary", "a summary"))));
		assertThat(result).isSuccess();

		verify(sentimentClient).analyze(eq("a summary"), anyString(), any());
	}

	@Test
	void testSecondRunServedFromCacheWithoutReclassification() {
		SentimentNode node = node();
		NodeResult first = node.process(ctxWithTika("Der Kundenservice war eine Katastrophe."));
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctxWithTika("Der Kundenservice war eine Katastrophe."));
		assertThat(second).isSuccess();
		assertEquals(first.get(SentimentNode.OUTPUT_SENTIMENT_RESULT), second.get(SentimentNode.OUTPUT_SENTIMENT_RESULT));
		assertEquals(first.get(SentimentNode.OUTPUT_SENTIMENT_LABEL), second.get(SentimentNode.OUTPUT_SENTIMENT_LABEL));

		// The sidecar must be hit exactly once - the second run is served from the in-heap cache.
		verify(sentimentClient, times(1)).analyze(anyString(), anyString(), any());
	}

	@Test
	void testEmitsNoOutputAndDoesNotCacheWhenSidecarThrows() {
		// First call fails, second succeeds - so the retry below exercises a cold cache rather than a re-stub.
		when(sentimentClient.analyze(anyString(), anyString(), any()))
			.thenThrow(new RuntimeException("sidecar down"))
			.thenReturn(SIDECAR_RESULT.copy());

		SentimentNode node = node();
		NodeResult result = node.process(ctxWithTika("Der Kundenservice war eine Katastrophe."));

		// The node takes the ctx.failure(...).next() path shared by every node in the tree; NodeContextImpl.next() reports SUCCESS regardless of the
		// recorded failure cause (only abort() yields FAILED). What is observable here is that nothing was emitted and nothing was cached - the
		// FAILED ledger row is asserted in SentimentNodePersistenceTest.
		assertNull(result.get(SentimentNode.OUTPUT_SENTIMENT_LABEL), "A failed run must not emit a label");
		assertNull(result.get(SentimentNode.OUTPUT_SENTIMENT_RESULT), "A failed run must not emit a result payload");

		// A failed run must not poison the skip cache - the next run has to hit the sidecar again.
		NodeResult retry = node.process(ctxWithTika("Der Kundenservice war eine Katastrophe."));
		assertThat(retry).isSuccess();
		assertEquals("NEGATIVE", retry.get(SentimentNode.OUTPUT_SENTIMENT_LABEL));
	}

	@Test
	void testDisabledNodeIsSkipped() {
		SentimentNodeOptions options = new SentimentNodeOptions();
		options.setEnabled(false);

		NodeResult result = node(options).process(ctxWithTika("Der Kundenservice war eine Katastrophe."));
		assertThat(result).isSkipped();
	}
}
