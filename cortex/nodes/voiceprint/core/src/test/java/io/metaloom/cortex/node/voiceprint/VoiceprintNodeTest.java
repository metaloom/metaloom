package io.metaloom.cortex.node.voiceprint;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonObject;

/**
 * Deterministic unit test for {@link VoiceprintNode}. The sidecar is a mocked {@link VoiceprintClient}; the video path runs the real ffmpeg demux
 * when ffmpeg is on PATH.
 */
class VoiceprintNodeTest {

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private VoiceprintClient client;
	private StubLoomMedia audio;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(VoiceprintClient.class);
		when(client.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.result());
		File wav = VoiceprintFixtures.toneWav(tempDir, "interview.wav");
		audio = new StubLoomMedia(wav.getAbsolutePath(), false, false, true, false);
	}

	private VoiceprintNode node(VoiceprintNodeOptions options) {
		return new VoiceprintNode(null, cortexOptions, options, client);
	}

	private VoiceprintNode node() {
		return node(new VoiceprintNodeOptions());
	}

	@Test
	void testEmitsSpeakersTimelineAndVoiceprint() {
		NodeContext<LoomMedia> ctx = NodeContext.create(audio);
		NodeResult result = node().process(ctx);
		assertThat(result).isSuccess();
		assertEquals(ResultOrigin.COMPUTED, ctx.resultOrigin());

		assertEquals(2L, result.get(VoiceprintNode.OUT_SPEAKER_COUNT));

		List<?> speakers = result.elements(VoiceprintNode.OUT_SPEAKERS);
		assertEquals(2, speakers.size(), "one element per speaker");

		JsonObject voiceprint = new JsonObject((String) result.get(VoiceprintNode.OUT_VOICEPRINT));
		assertEquals(VoiceprintFixtures.MODEL, voiceprint.getString("model"));
		assertEquals(3, voiceprint.getJsonArray("vector").size());

		JsonObject timeline = new JsonObject((String) result.get(VoiceprintNode.OUT_TIMELINE));
		assertEquals(2, timeline.getJsonArray("segments").size());
		assertEquals(1_000L, timeline.getJsonArray("segments").getJsonObject(0).getLong("start"));
		assertEquals(0, timeline.getJsonArray("segments").getJsonObject(0).getInteger("speaker"));
		assertEquals("Speaker 2", timeline.getJsonArray("segments").getJsonObject(1).getString("label"));

		JsonObject summary = new JsonObject((String) result.get(VoiceprintNode.OUT_RESULT));
		assertEquals(2, summary.getInteger("speakerCount"));
		assertFalse(summary.encode().contains("vector"), "the summary is the timeline without the vectors");
	}

	@Test
	void testOptionsReachTheSidecar() {
		VoiceprintNodeOptions options = new VoiceprintNodeOptions().setModel("wespeaker").setWindowSeconds(2.0).setHopSeconds(1.0)
			.setClusterThreshold(0.55).setMaxSpeakers(4);
		options.setTimeoutMs(1234);
		assertThat(node(options).process(NodeContext.create(audio))).isSuccess();
		verify(client).analyze(any(), argThat(p -> "wespeaker".equals(p.model()) && p.windowSeconds() == 2.0 && p.hopSeconds() == 1.0
			&& p.clusterThreshold() == 0.55 && p.maxSpeakers() == 4), eq(1234L));
	}

	@Test
	void testDefaultsLeaveModelAndThresholdToTheSidecar() {
		VoiceprintClient.Params p = node().params();
		assertNull(p.model(), "a blank model means the sidecar's default backend");
		assertNull(p.clusterThreshold(), "0 means the backend's calibrated threshold");
		assertNull(p.maxSpeakers());
		assertEquals(3.0, p.windowSeconds());
	}

	@Test
	void testAudioIsUploadedAsIs() {
		node().process(NodeContext.create(audio));
		verify(client).analyze(argThat(path -> path.toString().equals(audio.absolutePath())), any(), anyLong());
	}

	@Test
	void testVideoIsDemuxedToWavAndTheTempFileRemoved() throws Exception {
		assumeTrue(VoiceprintFixtures.ffmpegAvailable(), "ffmpeg not on PATH");
		// Any container ffmpeg reads stands in for a video: the node demuxes whatever is flagged as video.
		File source = VoiceprintFixtures.toneWav(tempDir, "clip.wav");
		StubLoomMedia video = new StubLoomMedia(source.getAbsolutePath(), true, false, false, false);
		AtomicReference<Path> uploaded = new AtomicReference<>();
		AtomicReference<Boolean> wasWav = new AtomicReference<>(false);
		when(client.analyze(any(), any(), anyLong())).thenAnswer(inv -> {
			Path p = inv.getArgument(0);
			uploaded.set(p);
			byte[] head = Files.readAllBytes(p);
			wasWav.set(new String(head, 0, 4).equals("RIFF") && new String(head, 8, 4).equals("WAVE"));
			return VoiceprintFixtures.result();
		});

		assertThat(node().process(NodeContext.create(video))).isSuccess();
		assertNotEquals(source.toPath(), uploaded.get(), "a video must not be uploaded whole");
		assertTrue(wasWav.get(), "the upload is the demuxed WAV");
		assertFalse(Files.exists(uploaded.get()), "the demuxed temp file is removed");
	}

	@Test
	void testMissingFfmpegFailsRatherThanSkips() throws Exception {
		File source = VoiceprintFixtures.toneWav(tempDir, "clip2.wav");
		StubLoomMedia video = new StubLoomMedia(source.getAbsolutePath(), true, false, false, false);
		NodeResult result = node(new VoiceprintNodeOptions().setFfmpegPath("/nonexistent/ffmpeg")).process(NodeContext.create(video));
		assertThat(result).isFailed();
		verify(client, never()).analyze(any(), any(), anyLong());
	}

	@Test
	void testSilenceIsSkipped() {
		when(client.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.silent());
		assertThat(node().process(NodeContext.create(audio))).isSkipped();
	}

	@Test
	void testSidecarFailureIsFailed() {
		when(client.analyze(any(), any(), anyLong())).thenThrow(new RuntimeException("sidecar down"));
		assertThat(node().process(NodeContext.create(audio))).isFailed();
	}

	@Test
	void testImagesAndDocumentsAreNotProcessable() throws Exception {
		StubLoomMedia image = StubLoomMedia.ofBytes(tempDir, "photo.jpg", "fake-image");
		StubLoomMedia img = new StubLoomMedia(image.file().getAbsolutePath(), false, true, false, false);
		assertThat(node().process(NodeContext.create(img))).isSkipped();
		verify(client, never()).analyze(any(), any(), anyLong());
	}

	@Test
	void testSecondRunIsServedFromTheCache() {
		VoiceprintNode node = node();
		assertThat(node.process(NodeContext.create(audio))).isSuccess();
		NodeContext<LoomMedia> second = NodeContext.create(audio);
		NodeResult again = node.process(second);
		assertThat(again).isSuccess();
		assertEquals(ResultOrigin.LOCAL, second.resultOrigin(), "a cache hit is SUCCESS from LOCAL, not SKIPPED");
		assertEquals(2L, again.get(VoiceprintNode.OUT_SPEAKER_COUNT));
		verify(client, times(1)).analyze(any(), any(), anyLong());
	}

	@Test
	void testChangedOptionsMissTheCache() {
		VoiceprintNodeOptions options = new VoiceprintNodeOptions();
		VoiceprintNode node = node(options);
		node.process(NodeContext.create(audio));
		options.setModel("speechbrain");
		node.process(NodeContext.create(audio));
		verify(client, times(2)).analyze(any(), any(), anyLong());
	}

	@Test
	void testRewrittenFileMissesTheCache() throws Exception {
		VoiceprintNode node = node();
		node.process(NodeContext.create(audio));
		Files.write(audio.path(), new byte[] { 1, 2, 3 });
		node.process(NodeContext.create(audio));
		verify(client, times(2)).analyze(any(), any(), anyLong());
	}

	@Test
	void testPreviewTableNeverThrows() {
		String md = VoiceprintNode.speakersMarkdown(VoiceprintFixtures.result());
		assertTrue(md.startsWith("| speaker |"));
		assertTrue(md.contains("| 1 | 0:06.0 | 1 | 0.93 | 0:01.0 |"), md);
		assertEquals("0:00.0", VoiceprintNode.clock(0));
		assertEquals("1:05.2", VoiceprintNode.clock(65_250));
	}

	@Test
	void testCosine() {
		assertEquals(1f, VoiceprintNode.cosine(new float[] { 1, 0 }, new float[] { 2, 0 }), 1e-6);
		assertEquals(0f, VoiceprintNode.cosine(new float[] { 1, 0 }, new float[] { 0, 1 }), 1e-6);
		assertEquals(0f, VoiceprintNode.cosine(new float[] { 1 }, new float[] { 1, 0 }));
		assertEquals(0f, VoiceprintNode.cosine(null, new float[] { 1 }));
	}
}
