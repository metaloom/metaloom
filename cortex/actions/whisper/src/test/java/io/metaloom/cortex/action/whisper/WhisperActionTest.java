package io.metaloom.cortex.action.whisper;

import static io.metaloom.cortex.api.media.LoomMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.ActionAssertions.assertThat;
import static io.metaloom.cortex.media.whisper.WhisperMedia.WHISPER;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.action.ActionResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.type.handler.impl.AvroLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.FSLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.HeapLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.XAttrLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.meta.MetaStorageImpl;
import io.metaloom.cortex.media.test.AbstractBasicActionTest;
import io.metaloom.cortex.media.whisper.WhisperMedia;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class WhisperActionTest extends AbstractBasicActionTest<WhisperAction> {

	private static final String MODEL_PATH = "/home/defaultuser/workspaces/metaloom/whisper.cpp/models/ggml-large-v3-turbo.bin";

	/**
	 * Override to bypass LoomClientMock which fails with Java 25 Mockito restrictions.
	 */
	@Override
	public WhisperAction mockAction() {
		return mockAction(null, options());
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
		WhisperAction actionMock = action();

		ActionResult result = actionMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, actionMock);

		// Run the process again on the media to ensure that it will be skipped
		ActionResult result2 = actionMock.process(ctx(media));
		assertThat(result2).isSkipped();
	}

	@Test
	@Override
	public void testProcessVideo() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperAction actionMock = action();

		ActionResult result = actionMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, actionMock);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, ActionResult result, WhisperAction actionMock) {
		WhisperMedia whisperMedia = media.of(WHISPER);
		assertThat(media).hasXAttr(SHA_512_KEY);

		WhisperResult whisperResult = whisperMedia.getWhisperResult();
		System.out.println("Segments: " + whisperResult.segments().size());
		for (var segment : whisperResult.segments()) {
			System.out.println("[" + segment.getFrom() + " - " + segment.getTo() + "] " + segment.getText());
		}
		org.junit.jupiter.api.Assertions.assertFalse(whisperResult.segments().isEmpty(), "Whisper should have produced transcription segments");
	}

	@Override
	protected void assertProcessedImage(WhisperAction actionMock, LoomMedia media, TestMedia image) throws IOException {
		assertSkipped(actionMock, media);
	}

	@Override
	protected void assertProcessedDoc(WhisperAction actionMock, LoomMedia media, TestMedia docMedia) throws IOException {
		assertSkipped(actionMock, media);
	}

	@Override
	protected void assertProcessedAudio(WhisperAction actionMock, LoomMedia media, TestMedia audio) throws IOException {
		// Audio processing is not yet supported - skip for now
		assertSkipped(actionMock, media);
	}

	@Test
	public void testProcessVideoGPU() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperAction actionMock = mockActionWithGpu(true);

		ActionResult result = actionMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, actionMock);
	}

	@Test
	public void testProcessVideoCPU() throws IOException {
		TestMedia jfk = jfkVideo();
		LoomMedia media = media(jfk);
		WhisperAction actionMock = mockActionWithGpu(false);

		ActionResult result = actionMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(jfk, media, result, actionMock);
	}

	/**
	 * Create a WhisperAction with explicit GPU setting.
	 *
	 * @param useGpu
	 *            true to use GPU (CUDA), false to force CPU-only execution
	 * @return configured WhisperAction
	 */
	private WhisperAction mockActionWithGpu(boolean useGpu) {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath(MODEL_PATH);
		options.setTemperature(0.0f);
		options.setTemperatureInc(0.2f);
		options.setLanguage("en");
		options.setUseGpu(useGpu);
		WhisperMediaProcessor processor = new WhisperMediaProcessor(options);
		return new WhisperAction(null, options(), options, processor);
	}

	@Override
	protected void disableAction(WhisperAction actionMock) {
		WhisperOptions options = actionMock.options();
		options.setEnabled(false);
	}

	@Override
	public WhisperAction mockAction(LoomClient client, CortexOptions cortexOptions) {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath(MODEL_PATH);
		options.setTemperature(0.0f);
		options.setTemperatureInc(0.2f);
		options.setLanguage("en");
		WhisperMediaProcessor processor = new WhisperMediaProcessor(options);
		// Pass null for client to use offline mode
		return new WhisperAction(null, cortexOptions, options, processor);
	}

	@Override
	public MetaStorage storage() {
		return new MetaStorageImpl(
			Set.of(new HeapLoomMetaTypeHandlerImpl(), new AvroLoomMetaTypeHandlerImpl(options()),
				new XAttrLoomMetaTypeHandlerImpl(), new FSLoomMetaTypeHandlerImpl(options())));
	}

}
