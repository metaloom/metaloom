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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link SentimentNode}. The FastAPI sidecar is replaced by a mocked {@link SentimentClient}, so no server is required.
 * Verifies the text-in / label-out flow, truncation, and the in-heap skip cache.
 *
 * <p>
 * The text is now wired directly into the declared {@link SentimentNode#IN_TEXT} port instead of being resolved by the node from an ordered list of
 * upstream node/output-key pairs ({@code textSources}, since deleted) - the edge decides where the text comes from, the node just reads its port.
 * </p>
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

	private NodeContext<LoomMedia> ctxWithText(String text) {
		NodeInputs inputs = NodeInputs.builder().input(SentimentNode.IN_TEXT, text).build();
		return NodeContext.create(media, inputs);
	}

	@Test
	void testScoresUpstreamText() {
		NodeResult result = node().process(ctxWithText("Der Kundenservice war eine Katastrophe."));
		assertThat(result).isSuccess();

		assertEquals("NEGATIVE", result.get(SentimentNode.OUT_LABEL));
		assertEquals(0.87d, result.get(SentimentNode.OUT_SCORE));

		String json = result.get(SentimentNode.OUT_RESULT);
		assertNotNull(json, "The node should emit the full result payload");
		JsonObject payload = new JsonObject(json);
		assertEquals(-0.81d, payload.getDouble("polarity"));
		assertEquals("oliverguhr/german-sentiment-bert", payload.getString("model"));
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
		NodeResult result = node().process(ctxWithText("   "));
		assertThat(result).isSkipped();
	}

	@Test
	void testTextTruncatedToMaxChars() {
		SentimentNodeOptions options = new SentimentNodeOptions().setMaxChars(10);

		NodeResult result = node(options).process(ctxWithText("0123456789ABCDEF"));
		assertThat(result).isSuccess();

		verify(sentimentClient).analyze(eq("0123456789"), anyString(), any());
	}

	@Test
	void testModelOverridesPassedToSidecar() {
		SentimentNodeOptions options = new SentimentNodeOptions()
			.setModelDe("scherrmann/GermanFinBert_SC_Sentiment")
			.setLanguage("de");

		assertThat(node(options).process(ctxWithText("Die Quartalszahlen enttäuschen."))).isSuccess();

		verify(sentimentClient).analyze(anyString(), eq("de"),
			eq(new JsonObject().put("de", "scherrmann/GermanFinBert_SC_Sentiment")));
	}

	@Test
	void testSecondRunServedFromCacheWithoutReclassification() {
		SentimentNode node = node();
		NodeResult first = node.process(ctxWithText("Der Kundenservice war eine Katastrophe."));
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctxWithText("Der Kundenservice war eine Katastrophe."));
		assertThat(second).isSuccess();
		assertEquals(first.get(SentimentNode.OUT_RESULT), second.get(SentimentNode.OUT_RESULT));
		assertEquals(first.get(SentimentNode.OUT_LABEL), second.get(SentimentNode.OUT_LABEL));

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
		NodeResult result = node.process(ctxWithText("Der Kundenservice war eine Katastrophe."));

		// The node reports the failure it recorded. It did not until 2026-08-18: it ended the catch block
		// with ctx.failure(cause).next(), and NodeContextImpl.next() read only skipReason, so the result
		// came back SUCCESS with a null message. The FAILED ledger row is asserted in
		// SentimentNodePersistenceTest.
		assertThat(result).isFailed().hasMessage("sidecar down");
		assertNull(result.get(SentimentNode.OUT_LABEL), "A failed run must not emit a label");
		assertNull(result.get(SentimentNode.OUT_RESULT), "A failed run must not emit a result payload");

		// A failed run must not poison the skip cache - the next run has to hit the sidecar again.
		NodeResult retry = node.process(ctxWithText("Der Kundenservice war eine Katastrophe."));
		assertThat(retry).isSuccess();
		assertEquals("NEGATIVE", retry.get(SentimentNode.OUT_LABEL));
	}

	@Test
	void testDisabledNodeIsSkipped() {
		SentimentNodeOptions options = new SentimentNodeOptions();
		options.setEnabled(false);

		NodeResult result = node(options).process(ctxWithText("Der Kundenservice war eine Katastrophe."));
		assertThat(result).isSkipped();
	}
}
