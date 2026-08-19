package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.whisper.TranscriptionSegment;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Verifies the two-step persistence contract - write the transcript payload, then record the {@code asset_node_result} ledger row - without invoking
 * native Whisper.cpp. The media processor and Loom client are mocked, so no model file is required. This is the deterministic counterpart to
 * {@link WhisperNodeTest}, whose real-transcription cases are gated on a local model.
 */
class WhisperNodePersistenceTest {

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private WhisperMediaProcessor processor;
	private LoomMedia media;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		TranscriptResponse transcript = new TranscriptResponse().setUuid(UUID.randomUUID());
		LoomClientRequest<TranscriptResponse> transcriptReq = mock(LoomClientRequest.class);
		when(transcriptReq.sync()).thenReturn(new LoomClientResponseImpl<>(transcript, 201, "Created", Map.of()));
		when(client.createAssetTranscript(any(), any())).thenReturn(transcriptReq);

		LoomClientRequest<NodeResultResponse> nodeResultReq = mock(LoomClientRequest.class);
		when(nodeResultReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);

		processor = mock(WhisperMediaProcessor.class);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.wav", "fake-audio");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
	}

	private WhisperNode node() {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath("models/ggml-base.bin");
		options.setLanguage("en");
		return new WhisperNode(client, new CortexOptions(), options, processor);
	}

	@Test
	void testPersistsTranscriptAndLedgerOnSuccess() throws Exception {
		WhisperResult result = new WhisperResult();
		result.addTranscriptionSegment(new TranscriptionSegment("Hello world", 0, 5000));
		when(processor.process(anyString())).thenReturn(result);

		NodeResult nodeResult = node().process(NodeContext.create(media));
		assertThat(nodeResult).isSuccess();

		verify(client).createAssetTranscript(eq(assetUuid), any(TranscriptCreateRequest.class));
		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "whisper".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null
			&& "asset_transcript_comp".equals(r.getResultRef().getString("table"))));
	}

	@Test
	void testRecordsFailedLedgerWhenProcessorThrows() throws Exception {
		when(processor.process(anyString())).thenThrow(new RuntimeException("boom"));

		NodeResult result = node().process(NodeContext.create(media));

		// A run whose transcript silently failed used to be indistinguishable from one that worked -
		// ctx.failure(cause).next() reported SUCCESS. The state and the cause are both part of the
		// contract now.
		assertThat(result).isFailed().hasMessage("boom");
		verify(client, never()).createAssetTranscript(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "boom".equals(r.getReason())));
	}
}
