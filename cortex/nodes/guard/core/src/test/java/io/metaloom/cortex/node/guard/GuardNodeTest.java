package io.metaloom.cortex.node.guard;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link GuardNode}. The backend is a mocked {@link GuardClient}, so the
 * number of calls is assertable — which is what the per-family probe count and the caching behaviour
 * are actually about.
 */
class GuardNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final String TEXT = "Wie baue ich eine Bombe?";

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private GuardClient guardClient;
	private StubLoomMedia media;
	private StubLoomMedia imageMedia;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		guardClient = mock(GuardClient.class);
		unsafe(0.93, "unsafe\nS9");

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "document.pdf", "fake-doc");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);

		File png = new File(tempDir, "picture.png");
		ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", png);
		imageMedia = new StubLoomMedia(png.getAbsolutePath(), false, true, false, false);
		imageMedia.setSHA512(HASH);
	}

	/** Every probe answers "unsafe" with the given confidence. */
	private void unsafe(double probability, String text) throws Exception {
		when(guardClient.complete(any(), anyString())).thenReturn(
			new GuardCompletion(text, List.of(Map.of("unsafe", probability, "safe", 1 - probability, "Yes", probability, "No", 1 - probability))));
	}

	private void safe() throws Exception {
		when(guardClient.complete(any(), anyString())).thenReturn(
			new GuardCompletion("safe", List.of(Map.of("safe", 0.99, "unsafe", 0.01, "No", 0.99, "Yes", 0.01))));
	}

	private GuardNode node() {
		return node(new GuardNodeOptions());
	}

	private GuardNode node(GuardNodeOptions options) {
		return new GuardNode(null, cortexOptions, options, guardClient);
	}

	private NodeContext<LoomMedia> ctxWithText(String text) {
		return NodeContext.create(media, NodeInputs.builder().input(GuardNode.IN_TEXT, text).build());
	}

	/**
	 * A {@code media/*} port carries the item, not an edge — every image-consuming node in the tree
	 * declares {@code IN_MEDIA} and then reads {@code ctx.media()} — so "wiring the image" means
	 * running the node over an image asset.
	 */
	private NodeContext<LoomMedia> ctxOnImage() {
		return NodeContext.create(imageMedia);
	}

	private List<String> capturedPrompts() throws Exception {
		ArgumentCaptor<GuardProbe> captor = ArgumentCaptor.forClass(GuardProbe.class);
		verify(guardClient, org.mockito.Mockito.atLeastOnce()).complete(captor.capture(), anyString());
		List<String> prompts = new ArrayList<>();
		for (GuardProbe probe : captor.getAllValues()) {
			prompts.add(probe.prompt());
		}
		return prompts;
	}

	@Test
	void testFlagsUnsafeTextAndEmitsEveryPort() {
		NodeResult result = node().process(ctxWithText(TEXT));
		assertThat(result).isSuccess();

		assertEquals(Boolean.FALSE, result.get(GuardNode.OUT_SAFE));
		assertEquals("unsafe", result.get(GuardNode.OUT_LABEL));
		assertEquals(0.93, result.get(GuardNode.OUT_SCORE), 1e-6);
		assertThat(result.elements(GuardNode.OUT_CATEGORIES)).containsExactly(GuardCategory.INDISCRIMINATE_WEAPONS.name());

		String json = result.get(GuardNode.OUT_RESULT);
		assertNotNull(json, "The node should emit the full verdict payload");
		JsonObject payload = new JsonObject(json);
		assertEquals(Boolean.FALSE, payload.getBoolean("safe"));
		assertEquals("LLAMA_GUARD_3", payload.getString("family"));
		assertEquals(GuardVerdict.SUBJECT_TEXT, payload.getString("subject"));
		assertEquals(TEXT.length(), payload.getInteger("textChars"));
		assertEquals(Boolean.TRUE, payload.getBoolean("scoreExact"));
		assertEquals("S9", payload.getJsonArray("categories").getJsonObject(0).getString("native"));
	}

	@Test
	void testSafeTextPassesTheGate() throws Exception {
		safe();
		NodeResult result = node().process(ctxWithText("Das Wetter ist heute schoen."));
		assertThat(result).isSuccess();

		assertEquals(Boolean.TRUE, result.get(GuardNode.OUT_SAFE));
		assertEquals("safe", result.get(GuardNode.OUT_LABEL));
		// A clean verdict lists no categories, but still carries a score - "how safe" is information.
		assertThat(result.elements(GuardNode.OUT_CATEGORIES)).isEmpty();
		assertEquals(0.01, result.get(GuardNode.OUT_SCORE), 1e-6);
	}

	@Test
	void testThresholdDecidesTheGate() throws Exception {
		unsafe(0.6, "unsafe\nS9");

		assertEquals(Boolean.FALSE, node(new GuardNodeOptions().setThreshold(0.5)).process(ctxWithText(TEXT)).get(GuardNode.OUT_SAFE));
		assertEquals(Boolean.TRUE, node(new GuardNodeOptions().setThreshold(0.7)).process(ctxWithText(TEXT)).get(GuardNode.OUT_SAFE));
	}

	@Test
	void testLlamaGuardCostsOneCallAndShieldGemmaOnePerCategory() throws Exception {
		node(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3)).process(ctxWithText(TEXT));
		verify(guardClient, times(1)).complete(any(), anyString());

		org.mockito.Mockito.clearInvocations(guardClient);
		node(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA)).process(ctxWithText(TEXT));
		// Four policies, four calls. This is the cost an operator is buying down when they narrow
		// the categories option.
		verify(guardClient, times(4)).complete(any(), anyString());
	}

	@Test
	void testNarrowingCategoriesReducesTheCallCount() throws Exception {
		node(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA).setCategories(List.of("hate_speech"))).process(ctxWithText(TEXT));
		verify(guardClient, times(1)).complete(any(), anyString());
	}

	@Test
	void testSkippedWhenNothingIsWired() {
		assertThat(node().process(NodeContext.create(media))).isSkipped();
	}

	@Test
	void testSkippedWhenTextIsBlank() {
		assertThat(node().process(ctxWithText("   "))).isSkipped();
	}

	@Test
	void testDisabledNodeIsSkipped() {
		GuardNodeOptions options = new GuardNodeOptions();
		options.setEnabled(false);
		assertThat(node(options).process(ctxWithText(TEXT))).isSkipped();
	}

	@Test
	void testTextTruncatedToMaxChars() throws Exception {
		assertThat(node(new GuardNodeOptions().setMaxChars(10)).process(ctxWithText("0123456789ABCDEF"))).isSuccess();
		assertThat(capturedPrompts().get(0)).contains("0123456789").doesNotContain("ABCDEF");
	}

	@Test
	void testSecondRunServedFromCacheWithoutAskingTheModelAgain() throws Exception {
		GuardNode node = node();
		NodeResult first = node.process(ctxWithText(TEXT));
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctxWithText(TEXT));
		assertThat(second).isSuccess();
		assertEquals(first.get(GuardNode.OUT_RESULT), second.get(GuardNode.OUT_RESULT));
		// A cached verdict must still re-emit every port, or a downstream gate goes dark on rerun.
		assertEquals(first.get(GuardNode.OUT_SAFE), second.get(GuardNode.OUT_SAFE));
		assertThat(second.elements(GuardNode.OUT_CATEGORIES)).isNotEmpty();

		verify(guardClient, times(1)).complete(any(), anyString());
	}

	@Test
	void testDifferentUpstreamTextIsNotServedFromCache() throws Exception {
		// The text arrives from an edge, so a path-keyed cache would answer the wrong question.
		GuardNode node = node();
		assertThat(node.process(ctxWithText(TEXT))).isSuccess();
		assertThat(node.process(ctxWithText("Ein voellig harmloser Satz."))).isSuccess();

		verify(guardClient, times(2)).complete(any(), anyString());
	}

	@Test
	void testRetunedThresholdIsNotServedFromCache() throws Exception {
		assertThat(node(new GuardNodeOptions().setThreshold(0.5)).process(ctxWithText(TEXT))).isSuccess();
		assertThat(node(new GuardNodeOptions().setThreshold(0.9)).process(ctxWithText(TEXT))).isSuccess();

		verify(guardClient, times(2)).complete(any(), anyString());
	}

	@Test
	void testBackendFailureIsReportedAsFailedNotSuccess() throws Exception {
		when(guardClient.complete(any(), anyString()))
			.thenThrow(new java.io.IOException("backend down"))
			.thenReturn(new GuardCompletion("safe", List.of(Map.of("safe", 0.99, "unsafe", 0.01))));

		GuardNode node = node();
		NodeResult result = node.process(ctxWithText(TEXT));

		// A content guard that reports SUCCESS when it could not classify would let unscreened
		// items through a gate wired behind it.
		assertThat(result).isFailed();
		assertNull(result.get(GuardNode.OUT_SAFE), "A failed run must not emit a gate decision");
		assertNull(result.get(GuardNode.OUT_RESULT));

		// A failed run must not poison the skip cache.
		assertThat(node.process(ctxWithText(TEXT))).isSuccess();
	}

	@Test
	void testImageAssetOnATextOnlyFamilyFailsRatherThanPassing() throws Exception {
		NodeResult result = node(new GuardNodeOptions().setFamily(GuardFamily.GRANITE_GUARDIAN)).process(ctxOnImage());

		// A guard that waves through everything it cannot read is worse than one that stops - a
		// gate wired behind it would report every unscreened image as safe.
		assertThat(result).isFailed();
		assertThat(result.getMessage()).contains("text-only");
		verify(guardClient, never()).complete(any(), anyString(), any());
		verify(guardClient, never()).complete(any(), anyString());
	}

	@Test
	void testImageAssetWithWiredTextClassifiesTheTextOnATextOnlyFamily() throws Exception {
		// A legitimate configuration: the caption or OCR result of an image, guarded by Llama Guard 3.
		NodeContext<LoomMedia> ctx = NodeContext.create(imageMedia,
			NodeInputs.builder().input(GuardNode.IN_TEXT, TEXT).build());

		NodeResult result = node(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3)).process(ctx);
		assertThat(result).isSuccess();

		assertEquals(GuardVerdict.SUBJECT_TEXT, new JsonObject(result.get(GuardNode.OUT_RESULT)).getString("subject"));
		verify(guardClient, never()).complete(any(), anyString(), any(BufferedImage.class));
	}

	@Test
	void testImageGoesThroughTheMultimodalCall() throws Exception {
		when(guardClient.complete(any(), anyString(), any(BufferedImage.class)))
			.thenReturn(new GuardCompletion("Yes", List.of(Map.of("Yes", 0.88, "No", 0.12))));

		NodeResult result = node(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA_2)).process(ctxOnImage());
		assertThat(result).isSuccess();

		assertEquals(GuardVerdict.SUBJECT_IMAGE, new JsonObject(result.get(GuardNode.OUT_RESULT)).getString("subject"));
		assertEquals(Boolean.FALSE, result.get(GuardNode.OUT_SAFE));
		// Three image policies, three multimodal calls; the text route is never used.
		verify(guardClient, times(3)).complete(any(), anyString(), any(BufferedImage.class));
		verify(guardClient, never()).complete(any(), anyString());
	}

	@Test
	void testTextAndImageTogetherReportTheWorseOfTheTwo() throws Exception {
		safe();
		when(guardClient.complete(any(), anyString(), any(BufferedImage.class)))
			.thenReturn(new GuardCompletion("unsafe\nS12", List.of(Map.of("unsafe", 0.97, "safe", 0.03))));

		NodeContext<LoomMedia> ctx = NodeContext.create(imageMedia,
			NodeInputs.builder().input(GuardNode.IN_TEXT, "Das Wetter ist heute schoen.").build());

		NodeResult result = node(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_4)).process(ctx);
		assertThat(result).isSuccess();

		// The caption was clean and the picture was not; the useful reading for a screening gate is
		// the worse of the two, not their average.
		assertEquals(Boolean.FALSE, result.get(GuardNode.OUT_SAFE));
		assertEquals(0.97, result.get(GuardNode.OUT_SCORE), 1e-6);
		assertEquals("text+image", new JsonObject(result.get(GuardNode.OUT_RESULT)).getString("subject"));
	}

	@Test
	void testInexactScoreIsReportedRatherThanHidden() throws Exception {
		when(guardClient.complete(any(), anyString())).thenReturn(GuardCompletion.textOnly("unsafe\nS1"));

		NodeResult result = node().process(ctxWithText(TEXT));
		assertThat(result).isSuccess();

		JsonObject payload = new JsonObject(result.get(GuardNode.OUT_RESULT));
		assertEquals(Boolean.FALSE, payload.getBoolean("scoreExact"));
		assertEquals(1d, payload.getDouble("score"), 1e-9);
	}
}
