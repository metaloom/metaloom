package io.metaloom.cortex.node.voiceprint;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.person.PersonMatchCandidate;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonMatchResult;
import io.metaloom.loom.rest.model.person.PersonPendingSample;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonPrintResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;

/**
 * Person suggestions on speaker clusters: matching the speakers against known voices, embedding the persons' own samples first, and the
 * {@code persons} output. Loom and the sidecar are mocked; the enrolment tests need ffmpeg to decode the sample.
 */
class VoiceprintPersonSuggestionTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ANNA = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");

	private static final UUID SAMPLE = UUID.fromString("05050505-0000-4000-8000-000000000005");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient loom;

	private VoiceprintClient sidecar;

	private StubLoomMedia media;

	private File sampleWav;

	private final List<PersonMatchRequest> matchRequests = new ArrayList<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		loom = mock(LoomHttpClient.class);

		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(new AssetResponse().setUuid(assetUuid), 200, "OK", Map.of()));
		when(loom.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		when(loom.bulkCreateAssetEmbeddings(any(UUID.class), any())).thenAnswer(inv -> {
			EmbeddingBulkCreateRequest req = inv.getArgument(1);
			EmbeddingBulkResponse body = new EmbeddingBulkResponse();
			req.getEmbeddings().forEach(e -> body.add(new EmbeddingResponse().setUuid(UUID.randomUUID())));
			LoomClientRequest<EmbeddingBulkResponse> r = mock(LoomClientRequest.class);
			when(r.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
			return r;
		});

		// Loom answers the bulk write the way it does for real: one response per cluster, the suggestion named.
		when(loom.bulkCreateAssetClusters(any(UUID.class), any())).thenAnswer(inv -> {
			ClusterBulkCreateRequest req = inv.getArgument(1);
			ClusterBulkResponse body = new ClusterBulkResponse();
			req.getClusters().forEach(item -> body.add(new ClusterResponse()
				.setType(item.getType())
				.setNodeKind(item.getNodeKind())
				.setClusterIndex(item.getClusterIndex())
				.setReviewStatus("PENDING")
				.setSuggestedPersonUuid(item.getSuggestedPersonUuid())
				.setSuggestedPersonName(item.getSuggestedPersonUuid() == null ? null : "Anna Meyer")
				.setSuggestedScore(item.getSuggestedScore())
				.setUuid(UUID.randomUUID())));
			LoomClientRequest<ClusterBulkResponse> r = mock(LoomClientRequest.class);
			when(r.sync()).thenReturn(new LoomClientResponseImpl<>(body, 201, "Created", Map.of()));
			return r;
		});

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(loom.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(loom.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		LoomClientRequest<PersonPrintResponse> printReq = mock(LoomClientRequest.class);
		when(printReq.sync()).thenReturn(new LoomClientResponseImpl<>(new PersonPrintResponse(), 201, "Created", Map.of()));
		when(loom.storePersonVoicePrint(any(UUID.class), any(UUID.class), any())).thenReturn(printReq);

		sidecar = mock(VoiceprintClient.class);
		when(sidecar.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.result());

		File wav = VoiceprintFixtures.toneWav(tempDir, "talk.wav");
		media = new StubLoomMedia(wav.getAbsolutePath(), false, false, true, false);
		media.setSHA512(HASH);
		sampleWav = VoiceprintFixtures.toneWav(tempDir, "sample.wav");
	}

	/** The first match answers with the given pending samples and no candidates; every later one suggests Anna for the first speaker. */
	@SuppressWarnings("unchecked")
	private void matchAnswers(boolean annaFromTheStart, PersonPendingSample... pending) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		when(loom.matchPersons(any())).thenAnswer(inv -> {
			PersonMatchRequest request = inv.getArgument(0);
			matchRequests.add(request);
			boolean first = calls.getAndIncrement() == 0;
			PersonMatchResponse response = new PersonMatchResponse();
			for (int i = 0; i < request.getVectors().size(); i++) {
				PersonMatchResult result = new PersonMatchResult().setIndex(i);
				if (i == 0 && (annaFromTheStart || !first)) {
					result.getCandidates().add(new PersonMatchCandidate().setPersonUuid(ANNA.toString()).setName("Anna Meyer").setScore(0.62f));
				}
				response.getResults().add(result);
			}
			if (first) {
				response.getPendingSamples().addAll(List.of(pending));
			}
			LoomClientRequest<PersonMatchResponse> r = mock(LoomClientRequest.class);
			when(r.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
			return r;
		});
	}

	@SuppressWarnings("unchecked")
	private void sampleDownloads() throws Exception {
		when(loom.downloadPersonVoiceSample(any(UUID.class), any(UUID.class))).thenAnswer(inv -> {
			LoomBinaryResponse binary = mock(LoomBinaryResponse.class);
			when(binary.getStream()).thenReturn(new ByteArrayInputStream(Files.readAllBytes(sampleWav.toPath())));
			LoomClientRequest<LoomBinaryResponse> r = mock(LoomClientRequest.class);
			when(r.sync()).thenReturn(new LoomClientResponseImpl<>(binary, 200, "OK", Map.of()));
			return r;
		});
	}

	private static PersonPendingSample pending() {
		return new PersonPendingSample().setPersonUuid(ANNA.toString()).setSampleUuid(SAMPLE.toString()).setMimeType("audio/wav");
	}

	private VoiceprintNode node(VoiceprintNodeOptions options) {
		return new VoiceprintNode(loom, new CortexOptions().setMetaPath(tempDir.toPath()), options, sidecar);
	}

	private NodeResult run(VoiceprintNode node) {
		return node.process(NodeContext.create(media));
	}

	private List<io.metaloom.loom.rest.model.cluster.ClusterCreateItem> writtenClusters() {
		ArgumentCaptor<ClusterBulkCreateRequest> captor = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(loom).bulkCreateAssetClusters(eq(assetUuid), captor.capture());
		return captor.getValue().getClusters();
	}

	/**
	 * The speakers are matched in the model's own space at its calibrated threshold, and the best candidate is written as a suggestion - only as a
	 * suggestion. The persons output names it.
	 */
	@Test
	void testTheClosestKnownVoiceIsSuggested() throws Exception {
		matchAnswers(true);
		NodeResult result = run(node(new VoiceprintNodeOptions()));
		assertThat(result).isSuccess();

		PersonMatchRequest request = matchRequests.get(0);
		assertEquals("speaker", request.getType());
		assertEquals(VoiceprintFixtures.MODEL, request.getModel());
		assertEquals(0.43f, request.getThreshold(), 1e-6, "ReDimNet2's calibrated threshold applies when none is configured");
		assertEquals(2, request.getVectors().size(), "one probe per speaker");

		var clusters = writtenClusters();
		assertEquals(ANNA.toString(), clusters.get(0).getSuggestedPersonUuid());
		assertEquals(0.62f, clusters.get(0).getSuggestedScore(), 1e-6);
		assertNull(clusters.get(1).getSuggestedPersonUuid(), "a voice like nobody's is suggested as nobody");

		JsonArray persons = new JsonArray(result.get(VoiceprintNode.OUT_PERSONS));
		assertEquals(1, persons.size());
		assertEquals(ANNA.toString(), persons.getJsonObject(0).getString("personUuid"));
		assertEquals("SUGGESTED", persons.getJsonObject(0).getString("status"));
		assertEquals("voice", persons.getJsonObject(0).getString("source"));
	}

	@Test
	void testAConfiguredThresholdWins() throws Exception {
		matchAnswers(true);
		assertThat(run(node(new VoiceprintNodeOptions().setSuggestThreshold(0.7)))).isSuccess();
		assertEquals(0.7f, matchRequests.get(0).getThreshold(), 1e-6);
	}

	@Test
	void testSuggestionsCanBeSwitchedOff() throws Exception {
		matchAnswers(true);
		NodeResult result = run(node(new VoiceprintNodeOptions().setSuggestPersons(false)));
		assertThat(result).isSuccess();
		verify(loom, never()).matchPersons(any());
		assertTrue(writtenClusters().stream().allMatch(c -> c.getSuggestedPersonUuid() == null));
		assertEquals("[]", result.get(VoiceprintNode.OUT_PERSONS), "the port is still written, so a filter wired to it runs");
	}

	/** Matching is a convenience on top of finding the speakers: when it fails, the speakers are still written, unsuggested. */
	@SuppressWarnings("unchecked")
	@Test
	void testAFailedMatchStillWritesTheSpeakers() throws Exception {
		LoomClientRequest<PersonMatchResponse> broken = mock(LoomClientRequest.class);
		when(broken.sync()).thenThrow(new RuntimeException("connection refused"));
		when(loom.matchPersons(any())).thenReturn(broken);

		assertThat(run(node(new VoiceprintNodeOptions()))).isSuccess();
		var clusters = writtenClusters();
		assertEquals(2, clusters.size());
		assertTrue(clusters.stream().allMatch(c -> c.getSuggestedPersonUuid() == null));
	}

	/**
	 * A sample uploaded to a person is embedded with this node's sidecar and model, stored as a print, and the speakers matched again - so the upload
	 * is what makes this recording recognisable.
	 */
	@Test
	void testAPendingSampleIsEnrolledAndTheMatchRepeated() throws Exception {
		Assumptions.assumeTrue(VoiceprintFixtures.ffmpegAvailable(), "ffmpeg decodes the sample");
		matchAnswers(false, pending());
		sampleDownloads();

		assertThat(run(node(new VoiceprintNodeOptions()))).isSuccess();

		verify(loom).storePersonVoicePrint(eq(ANNA), eq(SAMPLE), argThat((PersonPrintCreateRequest p) -> "speaker".equals(p.getType())
			&& VoiceprintFixtures.MODEL.equals(p.getModel()) && p.getVector() != null && p.getError() == null));
		verify(loom, times(2)).matchPersons(any());
		assertEquals(0, matchRequests.get(1).getPendingLimit(), "the repeat asks for no further work");
		assertEquals(ANNA.toString(), writtenClusters().get(0).getSuggestedPersonUuid(), "the repeat's answer is the one written");
		// The item itself, then the sample: two sidecar calls.
		verify(sidecar, times(2)).analyze(any(), any(), anyLong());
	}

	/** A sample with no speech is recorded as unusable, so it is not retried on every item; nothing new to match against, so no repeat. */
	@Test
	void testASilentSampleIsRecordedAsUnusable() throws Exception {
		Assumptions.assumeTrue(VoiceprintFixtures.ffmpegAvailable(), "ffmpeg decodes the sample");
		matchAnswers(false, pending());
		sampleDownloads();
		when(sidecar.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.result(), VoiceprintFixtures.silent());

		assertThat(run(node(new VoiceprintNodeOptions()))).isSuccess();

		verify(loom).storePersonVoicePrint(eq(ANNA), eq(SAMPLE), argThat((PersonPrintCreateRequest p) -> p.getVector() == null
			&& "no speech found".equals(p.getError())));
		verify(loom, times(1)).matchPersons(any());
	}

	/** A sidecar that is down while enrolling is transient: nothing is recorded, so the sample is tried again later. */
	@Test
	void testATransientEnrolmentFailureRecordsNothing() throws Exception {
		Assumptions.assumeTrue(VoiceprintFixtures.ffmpegAvailable(), "ffmpeg decodes the sample");
		matchAnswers(false, pending());
		sampleDownloads();
		when(sidecar.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.result()).thenThrow(new RuntimeException("sidecar busy"));

		assertThat(run(node(new VoiceprintNodeOptions()))).isSuccess();
		verify(loom, never()).storePersonVoicePrint(any(UUID.class), any(UUID.class), any());
	}

	/**
	 * A cache hit re-emits the analysis, but who the speakers are comes from Loom: a reviewer may have confirmed one since the first run.
	 */
	@SuppressWarnings("unchecked")
	@Test
	void testACacheHitReadsThePersonsFromLoom() throws Exception {
		matchAnswers(false);
		VoiceprintNode node = node(new VoiceprintNodeOptions());
		assertThat(run(node)).isSuccess();

		LoomClientRequest<ClusterListResponse> listReq = mock(LoomClientRequest.class);
		ClusterListResponse list = new ClusterListResponse();
		list.setData(new ArrayList<>(List.of(
			new ClusterResponse().setType("speaker").setNodeKind("voiceprint").setReviewStatus("CONFIRMED").setPersonUuid(ANNA.toString())
				.setPersonName("Anna Meyer").setClusterIndex(0),
			new ClusterResponse().setType("face").setNodeKind("facedetect").setReviewStatus("CONFIRMED").setPersonUuid(ANNA.toString()))));
		when(listReq.sync()).thenReturn(new LoomClientResponseImpl<>(list, 200, "OK", Map.of()));
		when(loom.listAssetClusters(any(UUID.class))).thenReturn(listReq);

		NodeResult second = run(node);
		assertThat(second).isSuccess();
		JsonArray persons = new JsonArray(second.get(VoiceprintNode.OUT_PERSONS));
		assertEquals(1, persons.size(), "only this node's speaker clusters; the face belongs to facedetect's output");
		assertEquals("CONFIRMED", persons.getJsonObject(0).getString("status"));
	}
}
