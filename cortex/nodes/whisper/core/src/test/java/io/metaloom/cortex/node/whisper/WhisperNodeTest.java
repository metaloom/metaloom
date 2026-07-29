package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class WhisperNodeTest extends AbstractBasicNodeTest<WhisperNode> {

	private static final String MODEL_PATH = "/home/defaultuser/workspaces/metaloom/whisper.cpp/models/ggml-large-v3-turbo.bin";

	/**
	 * Override to bypass LoomClientMock which fails with Java 25 Mockito restrictions.
	 */
	@Override
	public WhisperNode mockNode() {
		return mockNode(null, options());
	}

	/**
	 * Return test media pointing to the jfk.webm video file which contains clear speech for whisper testing.
	 */
	public TestMedia jfkVideo() {
		return testMedia("folderA/folderB/jfk.webm").build();
	}

	@Test
	@Override
	public void testProcessing() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperNode nodeMock = node();

		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, nodeMock);

		// Run the process again on the media to ensure that it will be skipped
		NodeResult result2 = nodeMock.process(ctx(media));
		assertThat(result2).isSkipped();
	}

	@Test
	@Override
	public void testProcessVideo() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperNode nodeMock = node();

		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, nodeMock);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, WhisperNode nodeMock) {
		assertThat(media).hasSHA512();

		String json = result.get(WhisperNode.OUT_TRANSCRIPT);
		WhisperResult whisperResult = WhisperResult.fromJson(json);
		System.out.println("Segments: " + whisperResult.segments().size());
		for (var segment : whisperResult.segments()) {
			System.out.println("[" + segment.getFrom() + " - " + segment.getTo() + "] " + segment.getText());
		}
		assertFalse(whisperResult.segments().isEmpty(), "Whisper should have produced transcription segments");
	}

	@Override
	protected void assertProcessedImage(WhisperNode nodeMock, LoomMedia media, TestMedia image) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedDoc(WhisperNode nodeMock, LoomMedia media, TestMedia docMedia) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedAudio(WhisperNode nodeMock, LoomMedia media, TestMedia audio) throws IOException {
		// Audio processing is not yet supported - skip for now
		assertSkipped(nodeMock, media);
	}

	@Test
	public void testProcessVideoGPU() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperNode nodeMock = mockNodeWithGpu(true);

		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, nodeMock);
	}

	@Test
	public void testProcessVideoCPU() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperNode nodeMock = mockNodeWithGpu(false);

		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, nodeMock);
	}

	/**
	 * Create a WhisperNode with explicit GPU setting.
	 *
	 * @param useGpu
	 *            true to use GPU (CUDA), false to force CPU-only execution
	 * @return configured WhisperNode
	 */
	private WhisperNode mockNodeWithGpu(boolean useGpu) {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath(MODEL_PATH);
		options.setTemperature(0.0f);
		options.setTemperatureInc(0.2f);
		options.setLanguage("en");
		options.setUseGpu(useGpu);
		WhisperMediaProcessor processor = new WhisperMediaProcessor(options);
		return new WhisperNode(null, options(), options, processor);
	}

	@Override
	protected void disableNode(WhisperNode nodeMock) {
		WhisperOptions options = nodeMock.options();
		options.setEnabled(false);
	}

	@Override
	public WhisperNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath(MODEL_PATH);
		options.setTemperature(0.0f);
		options.setTemperatureInc(0.2f);
		options.setLanguage("en");
		WhisperMediaProcessor processor = new WhisperMediaProcessor(options);
		// Pass null for client to use offline mode
		return new WhisperNode(null, cortexOptions, options, processor);
	}

}
