package io.metaloom.cortex.node.vlm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;

/**
 * Drives {@link VlmNode} against {@link MockLLMServer}, which serves the same OpenAI-compatible {@code /v1/chat/completions} protocol as vLLM. That keeps
 * the test honest about the wire format while needing no GPU: the node builds the real multimodal request, the mock answers with a canned olmOCR reply and
 * the node parses it exactly as it would a real one.
 *
 * <p>
 * The Loom client is null throughout, so the node runs in offline mode and skips persistence. The write-back path is covered end to end by
 * {@code VlmNodeIntegrationTest}.
 * </p>
 */
public class VlmNodeTest extends AbstractMediaTest {

	private MockLLMServer server;

	@AfterEach
	public void stopServer() {
		if (server != null) {
			server.stop();
			server = null;
		}
	}

	/** A representative olmOCR page reply: YAML front matter, then the transcribed page. */
	private static final String OLMOCR_REPLY = """
		---
		primary_language: de
		is_rotation_valid: True
		rotation_correction: 0
		is_table: False
		is_diagram: False
		---
		# Albert Einstein

		Albert Einstein war ein theoretischer Physiker.
		""";

	private VlmNode node(MockLLMServer server) {
		return node(server, VlmPromptPresets.olmOcr("mock-model"));
	}

	private VlmNode node(MockLLMServer server, VlmNodePrompt prompt) {
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl(server.baseUrl())
			.addPrompt(VlmPromptPresets.OLMOCR_ID, prompt);
		VlmChatClient client = new VlmChatClient(server.baseUrl(), null);
		// null Loom client = offline mode (no Loom server needed for unit tests)
		return new VlmNode(null, new CortexOptions(), options, client);
	}

	private LoomMedia ocrImage() {
		return media(data.root().resolve("ocr/albert_einstein.png"));
	}

	@Test
	public void testOlmOcrTranscription() throws IOException {
		server = MockLLMServer.create(0).addResponse(OLMOCR_REPLY).start();
		VlmNode node = node(server);

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		String text = result.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID));
		assertNotNull(text);
		assertTrue(text.contains("Albert Einstein"), "Expected the transcription, got: " + text);
		// The front matter must be stripped from the emitted text - it is metadata, not page content.
		assertFalse(text.contains("primary_language"), "Front matter leaked into the output text: " + text);
		assertEquals(0, server.remainingResponses(), "Expected exactly one model call");
	}

	@Test
	public void testFrontMatterIsParsed() {
		OlmOcrResponse parsed = OlmOcrResponseParser.parse(OLMOCR_REPLY);

		assertEquals("de", parsed.primaryLanguage());
		assertTrue(parsed.rotationValid());
		assertEquals(0, parsed.rotationCorrection());
		assertFalse(parsed.table());
		assertFalse(parsed.diagram());
		assertTrue(parsed.naturalText().startsWith("# Albert Einstein"));
	}

	/**
	 * A reply without front matter must still yield the page text rather than blowing up - losing a transcription over a missing header would be the worse
	 * failure.
	 */
	@Test
	public void testReplyWithoutFrontMatter() throws IOException {
		server = MockLLMServer.create(0).addResponse("Just some plain text with no header at all.").start();
		VlmNode node = node(server);

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals("Just some plain text with no header at all.", result.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)));
	}

	/**
	 * When olmOCR reports the page went in sideways it returns a rotation instead of a transcription. The node must turn the image and ask again.
	 */
	@Test
	public void testRetriesWhenModelReportsRotation() throws IOException {
		String rotated = """
			---
			primary_language: en
			is_rotation_valid: False
			rotation_correction: 90
			is_table: False
			is_diagram: False
			---
			""";
		server = MockLLMServer.create(0).addResponse(rotated).addResponse(OLMOCR_REPLY).start();
		VlmNode node = node(server);

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(0, server.remainingResponses(), "Expected the node to retry once after the rotation hint");
		assertTrue(result.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)).contains("Albert Einstein"),
			"Expected the retry's transcription to win");
	}

	/**
	 * With the retry disabled the node keeps the first (empty) answer instead of asking twice.
	 */
	@Test
	public void testRotationRetryCanBeDisabled() throws IOException {
		String rotated = """
			---
			is_rotation_valid: False
			rotation_correction: 270
			---
			""";
		server = MockLLMServer.create(0).addResponse(rotated).addResponse(OLMOCR_REPLY).start();
		VlmNode node = node(server, VlmPromptPresets.olmOcr("mock-model").setRetryOnRotation(false));

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(1, server.remainingResponses(), "Expected exactly one model call when the retry is disabled");
	}

	/**
	 * Hitting the output token limit truncates the page. The node must keep whatever arrived rather than discarding it.
	 */
	@Test
	public void testTruncatedReplyIsKeptAndFlagged() throws IOException {
		String cutOff = """
			---
			primary_language: en
			is_rotation_valid: True
			rotation_correction: 0
			is_table: False
			is_diagram: False
			---
			The beginning of a very long page that stops mid-sen""";
		server = MockLLMServer.create(0).addTruncatedResponse(cutOff).start();
		VlmNode node = node(server);

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertTrue(result.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)).startsWith("The beginning of a very long page"));
	}

	/**
	 * The second pass over the same file must come out of the in-heap cache without touching the endpoint. The mock has only one queued response, so a
	 * second call would fail with a 500.
	 */
	@Test
	public void testSecondRunIsServedFromTheLocalCache() throws IOException {
		server = MockLLMServer.create(0).addResponse(OLMOCR_REPLY).start();
		VlmNode node = node(server);
		LoomMedia media = ocrImage();

		NodeResult first = node.process(media);
		NodeResult second = node.process(media);

		assertEquals(ResultState.SUCCESS, first.getState());
		assertEquals(ResultState.SUCCESS, second.getState());
		assertEquals(first.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)),
			second.get(VlmNode.resultPort(VlmPromptPresets.OLMOCR_ID)));
		assertEquals(0, server.remainingResponses(), "The cached run must not call the endpoint again");
	}

	/**
	 * A non-2xx from the endpoint must surface as a node failure, not as an empty success.
	 */
	@Test
	public void testEndpointErrorFailsTheNode() throws IOException {
		server = MockLLMServer.create(0).addError(503, "service_unavailable", "The engine is loading").start();
		VlmNode node = node(server);

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.FAILED, result.getState());
	}

	/**
	 * A free-form prompt with the TEXT format keeps the reply verbatim.
	 */
	@Test
	public void testPlainTextResponseFormat() throws IOException {
		server = MockLLMServer.create(0).addResponse("A man in a suit looking at the camera.").start();
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl(server.baseUrl())
			.addPrompt("caption", new VlmNodePrompt()
				.setModel("mock-model")
				.setPrompt("Describe this image in one sentence.")
				.setResponseFormat(VlmResponseFormat.TEXT));
		VlmNode node = new VlmNode(null, new CortexOptions(), options, new VlmChatClient(server.baseUrl(), null));

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals("A man in a suit looking at the camera.", result.get(VlmNode.resultPort("caption")));
	}

	@Test
	public void testSkipsNonImageMedia() throws IOException {
		server = MockLLMServer.create(0).start();
		VlmNode node = node(server);

		NodeResult result = node.process(mediaVideo1());

		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	public void testDisabled() throws IOException {
		server = MockLLMServer.create(0).start();
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl(server.baseUrl())
			.addPrompt(VlmPromptPresets.OLMOCR_ID, VlmPromptPresets.olmOcr("mock-model"));
		options.setEnabled(false);
		VlmNode node = new VlmNode(null, new CortexOptions(), options, new VlmChatClient(server.baseUrl(), null));

		NodeResult result = node.process(ocrImage());

		assertEquals(ResultState.SKIPPED, result.getState());
	}

	/**
	 * A node configured without any prompt would silently do nothing, so the constructor installs the olmOCR preset.
	 */
	@Test
	public void testDefaultsToTheOlmOcrPreset() {
		VlmNodeOptions options = new VlmNodeOptions();
		new VlmNode(null, new CortexOptions(), options, new VlmChatClient("http://localhost:1", null));

		assertTrue(options.getPrompts().containsKey(VlmPromptPresets.OLMOCR_ID));
		assertEquals(VlmResponseFormat.OLMOCR, options.getPrompts().get(VlmPromptPresets.OLMOCR_ID).getResponseFormat());
		assertEquals(VlmPromptPresets.OLMOCR_MAX_IMAGE_DIM, options.getPrompts().get(VlmPromptPresets.OLMOCR_ID).getMaxImageDim());
	}
}
