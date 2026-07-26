package io.metaloom.cortex.node.tts;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Deterministic unit test for {@link TtsNode}. The FastAPI sidecar is replaced by a mocked {@link TtsClient}, so no server is required. Verifies the
 * text-in / audio-out flow: the WAV is written under {@code metaPath/tts_bin}, the output keys are emitted, and the node self-skips when no upstream
 * text is present.
 */
class TtsNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final byte[] FAKE_WAV = "RIFF....WAVEfake-audio".getBytes();

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private TtsClient ttsClient;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());

		ttsClient = mock(TtsClient.class);
		when(ttsClient.synthesize(anyString(), anyString(), anyString())).thenReturn(FAKE_WAV);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);
	}

	private TtsNode node() {
		return new TtsNode(null, cortexOptions, new TtsNodeOptions(), ttsClient);
	}

	private NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctxWithText(String text) {
		return NodeContext.create(media, Map.of("llm", Map.of("llm_result", text)));
	}

	@Test
	void testSynthesizesAndWritesWav() throws Exception {
		NodeResult result = node().process(ctxWithText("Hallo Welt"));
		assertThat(result).isSuccess();

		String outPath = result.get(TtsNode.OUTPUT_TTS_PATH);
		assertNotNull(outPath, "The node should emit the generated audio path");
		assertEquals("DONE", result.get(TtsNode.OUTPUT_TTS_FLAG));

		Path wav = Path.of(outPath);
		assertTrue(Files.exists(wav), "The WAV file should be written to the tts_bin cache");
		assertTrue(wav.startsWith(tempDir.toPath().resolve("tts_bin")), "The WAV should live under metaPath/tts_bin");
		assertEquals(FAKE_WAV.length, Files.size(wav));

		verify(ttsClient).synthesize(eq("Hallo Welt"), eq("de"), eq("Jakob"));
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
	void testSecondRunServedFromCacheWithoutResynthesis() throws Exception {
		TtsNode node = node();
		NodeResult first = node.process(ctxWithText("Hallo Welt"));
		assertThat(first).isSuccess();

		NodeResult second = node.process(ctxWithText("Hallo Welt"));
		assertThat(second).isSuccess();
		assertEquals(first.get(TtsNode.OUTPUT_TTS_PATH), second.get(TtsNode.OUTPUT_TTS_PATH));

		// The sidecar must be hit exactly once - the second run is served from the in-heap cache.
		verify(ttsClient, times(1)).synthesize(anyString(), anyString(), anyString());
	}
}
