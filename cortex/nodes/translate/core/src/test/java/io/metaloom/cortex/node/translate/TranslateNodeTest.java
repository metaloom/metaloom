package io.metaloom.cortex.node.translate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link TranslateNode}. The language model is replaced by a mocked
 * {@link LLMProvider}, so no backend is required and the number of model calls is assertable - which
 * is what the chunking and caching behaviour is actually about.
 *
 * <p>
 * The node is driven through a wired {@link TranslateNode#IN_TEXT} port rather than through media,
 * the same way {@code sentiment} and {@code tts} are: the edge decides where the text comes from.
 * </p>
 */
class TranslateNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final String GERMAN = "Der Kundenservice war eine Katastrophe.";

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private LLMProvider provider;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		provider = mock(LLMProvider.class);
		when(provider.generate(any(LLMContext.class))).thenReturn("The customer service was a disaster.");

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "interview.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private TranslateNode node() {
		return node(new TranslateNodeOptions());
	}

	private TranslateNode node(TranslateNodeOptions options) {
		return new TranslateNode(null, cortexOptions, options, provider);
	}

	private NodeContext<LoomMedia> ctxWithText(String text) {
		NodeInputs inputs = NodeInputs.builder().input(TranslateNode.IN_TEXT, text).build();
		return NodeContext.create(media, inputs);
	}

	/** The user prompt the provider was handed, as the model would see it. */
	private List<String> capturedPrompts() {
		ArgumentCaptor<LLMContext> captor = ArgumentCaptor.forClass(LLMContext.class);
		verify(provider, org.mockito.Mockito.atLeastOnce()).generate(captor.capture());
		List<String> inputs = new ArrayList<>();
		for (LLMContext ctx : captor.getAllValues()) {
			inputs.add(ctx.chatHistory().get(0).getText());
		}
		return inputs;
	}

	@Test
	void testTranslatesUpstreamText() {
		NodeResult result = node().process(ctxWithText(GERMAN));
		assertThat(result).isSuccess();

		assertEquals("The customer service was a disaster.", result.get(TranslateNode.OUT_TRANSLATION));
		assertEquals("en", result.get(TranslateNode.OUT_LANGUAGE));

		String json = result.get(TranslateNode.OUT_RESULT);
		assertNotNull(json, "The node should emit the full result payload");
		JsonObject payload = new JsonObject(json);
		assertEquals("en", payload.getString("targetLanguage"));
		assertEquals("auto", payload.getString("sourceLanguage"));
		assertEquals(TranslateNodeOptions.DEFAULT_MODEL, payload.getString("model"));
		assertEquals(1, payload.getInteger("chunkCount"));
		assertEquals(GERMAN.length(), payload.getInteger("sourceChars"));
	}

	@Test
	void testPromptCarriesLanguagesAndText() {
		TranslateNodeOptions options = new TranslateNodeOptions()
			.setTargetLanguage("French")
			.setSourceLanguage("German");

		assertThat(node(options).process(ctxWithText(GERMAN))).isSuccess();

		String prompt = capturedPrompts().get(0);
		assertThat(prompt).contains("French");
		assertThat(prompt).contains("German");
		// The placeholder must have been substituted, not passed through.
		assertThat(prompt).contains(GERMAN);
		assertThat(prompt).doesNotContain("${text}");
	}

	@Test
	void testSkippedWhenNoUpstreamText() {
		assertThat(node().process(NodeContext.create(media))).isSkipped();
	}

	@Test
	void testSkippedWhenUpstreamTextBlank() {
		assertThat(node().process(ctxWithText("   "))).isSkipped();
	}

	@Test
	void testDisabledNodeIsSkipped() {
		TranslateNodeOptions options = new TranslateNodeOptions();
		options.setEnabled(false);

		assertThat(node(options).process(ctxWithText(GERMAN))).isSkipped();
	}

	@Test
	void testTextTruncatedToMaxChars() {
		TranslateNodeOptions options = new TranslateNodeOptions().setMaxChars(10);

		assertThat(node(options).process(ctxWithText("0123456789ABCDEF"))).isSuccess();

		assertThat(capturedPrompts().get(0)).contains("0123456789").doesNotContain("ABCDEF");
	}

	@Test
	void testLongTextIsChunkedIntoOneCallPerChunk() {
		// Six paragraphs of ~50 chars against a 120 char budget: two per chunk, three chunks.
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			text.append("Absatz ").append(i).append(" enthaelt einen Satz zum Uebersetzen.\n\n");
		}
		TranslateNodeOptions options = new TranslateNodeOptions().setMaxChunkChars(200);

		NodeResult result = node(options).process(ctxWithText(text.toString()));
		assertThat(result).isSuccess();

		JsonObject payload = new JsonObject(result.get(TranslateNode.OUT_RESULT));
		int chunkCount = payload.getInteger("chunkCount");
		assertThat(chunkCount).isGreaterThan(1);

		// One model call per chunk, and the answers are rejoined rather than overwritten.
		verify(provider, times(chunkCount)).generate(any(LLMContext.class));
		assertThat(result.get(TranslateNode.OUT_TRANSLATION))
			.as("Every chunk's answer must survive into the joined translation")
			.contains("The customer service was a disaster.");
		assertThat(payload.getInteger("translatedChars")).isGreaterThan("The customer service was a disaster.".length());
	}

	@Test
	void testSecondRunServedFromCacheWithoutRetranslation() {
		TranslateNode node = node();
		NodeResult first = node.process(ctxWithText(GERMAN));
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctxWithText(GERMAN));
		assertThat(second).isSuccess();
		assertEquals(first.get(TranslateNode.OUT_TRANSLATION), second.get(TranslateNode.OUT_TRANSLATION));
		assertEquals(first.get(TranslateNode.OUT_RESULT), second.get(TranslateNode.OUT_RESULT));

		// The model must be hit exactly once - the second run is served from the in-heap cache.
		verify(provider, times(1)).generate(any(LLMContext.class));
	}

	@Test
	void testDifferentUpstreamTextIsNotServedFromCache() {
		// The cache is keyed on the input, not the media path: a second extractor feeding the same
		// asset different text must not be handed the first translation back.
		TranslateNode node = node();
		assertThat(node.process(ctxWithText(GERMAN))).isSuccess();
		assertThat(node.process(ctxWithText("Ein voellig anderer Satz."))).isSuccess();

		verify(provider, times(2)).generate(any(LLMContext.class));
	}

	@Test
	void testDifferentTargetLanguageIsNotServedFromCache() {
		// Two translate nodes on one asset, one per language. A path-keyed cache would hand the
		// second the first's English answer and label it French.
		assertThat(node(new TranslateNodeOptions().setTargetLanguage("en")).process(ctxWithText(GERMAN))).isSuccess();

		TranslateNode french = node(new TranslateNodeOptions().setTargetLanguage("fr"));
		NodeResult result = french.process(ctxWithText(GERMAN));
		assertThat(result).isSuccess();
		assertEquals("fr", result.get(TranslateNode.OUT_LANGUAGE));

		verify(provider, times(2)).generate(any(LLMContext.class));
	}

	@Test
	void testModelFailureIsReportedAsFailedNotSuccess() {
		when(provider.generate(any(LLMContext.class)))
			.thenThrow(new RuntimeException("backend down"))
			.thenReturn("The customer service was a disaster.");

		TranslateNode node = node();
		NodeResult result = node.process(ctxWithText(GERMAN));

		// The node aborts rather than returning next(): next() looks only at the skip reason, so a
		// failure returned that way is reported as SUCCESS. Fail, don't skip.
		assertThat(result).isFailed();
		assertNull(result.get(TranslateNode.OUT_TRANSLATION), "A failed run must not emit a translation");
		assertNull(result.get(TranslateNode.OUT_RESULT), "A failed run must not emit a result payload");

		// A failed run must not poison the skip cache - the next run has to call the model again.
		NodeResult retry = node.process(ctxWithText(GERMAN));
		assertThat(retry).isSuccess();
		assertEquals("The customer service was a disaster.", retry.get(TranslateNode.OUT_TRANSLATION));
	}
}
