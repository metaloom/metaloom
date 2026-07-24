package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.media.whisper.TranscriptionSegment;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.cortex.node.whisper.WhisperMediaProcessor;
import io.metaloom.cortex.node.whisper.WhisperNode;
import io.metaloom.cortex.node.whisper.WhisperOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Integration test for {@code WhisperNode}. A mocked {@link WhisperMediaProcessor} stands in for the whisper.cpp native runtime + model (the node's
 * transcription is not under test here), while the audio file, the {@link io.metaloom.loom.client.http.LoomHttpClient} and the Loom backend are real: the
 * transcript must reach the {@code asset_transcript_comp} table and be readable back through REST.
 */
public class WhisperNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testWhisperPersistsTranscriptComp() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, audio1(), "audio/mpeg");

			WhisperResult transcription = new WhisperResult();
			transcription.addTranscriptionSegment(new TranscriptionSegment("integration transcript", 0, 5000));
			WhisperMediaProcessor processor = mock(WhisperMediaProcessor.class);
			when(processor.process(anyString())).thenReturn(transcription);

			WhisperOptions options = new WhisperOptions();
			options.setModelPath("models/ggml-base.bin");
			options.setLanguage("en");
			WhisperNode node = new WhisperNode(client, cortexOptions(), options, processor);

			NodeResult result = node.process(NodeContext.create(media(audio1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			int transcripts = client.listAssetTranscripts(asset.getUuid()).sync().body().getData().size();
			assertThat(transcripts).as("transcript component must be readable via REST").isGreaterThanOrEqualTo(1);
		});
	}
}
