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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The persistence contract of {@link VoiceprintNode}: window embeddings with their time range, one speaker cluster per voice holding those
 * windows, the timeline component, then a SUCCESS ledger row pointing at the component. FAILED rows when the sidecar or Loom fails; a SKIPPED
 * row and no writes for silence. Sidecar and Loom client are mocked.
 */
class VoiceprintNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();
	private final List<UUID> embeddingUuids = new ArrayList<>();

	private LoomHttpClient loom;
	private VoiceprintClient sidecar;
	private StubLoomMedia media;

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
			for (int i = 0; i < req.getEmbeddings().size(); i++) {
				UUID u = UUID.randomUUID();
				embeddingUuids.add(u);
				body.add(new EmbeddingResponse().setUuid(u));
			}
			LoomClientRequest<EmbeddingBulkResponse> r = mock(LoomClientRequest.class);
			when(r.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
			return r;
		});

		LoomClientRequest<ClusterBulkResponse> clusterReq = mock(LoomClientRequest.class);
		when(clusterReq.sync()).thenReturn(new LoomClientResponseImpl<>(new ClusterBulkResponse(), 200, "OK", Map.of()));
		when(loom.bulkCreateAssetClusters(any(UUID.class), any())).thenReturn(clusterReq);

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(loom.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(loom.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		sidecar = mock(VoiceprintClient.class);
		when(sidecar.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.result());

		File wav = VoiceprintFixtures.toneWav(tempDir, "talk.wav");
		media = new StubLoomMedia(wav.getAbsolutePath(), false, false, true, false);
		media.setSHA512(HASH);
	}

	private VoiceprintNode node(VoiceprintNodeOptions options) {
		return new VoiceprintNode(loom, new CortexOptions().setMetaPath(tempDir.toPath()), options, sidecar);
	}

	@Test
	void testWritesWindowsClustersComponentAndLedger() throws Exception {
		assertThat(node(new VoiceprintNodeOptions()).process(NodeContext.create(media))).isSuccess();

		ArgumentCaptor<EmbeddingBulkCreateRequest> emb = ArgumentCaptor.forClass(EmbeddingBulkCreateRequest.class);
		verify(loom).bulkCreateAssetEmbeddings(eq(assetUuid), emb.capture());
		List<EmbeddingCreateRequest> rows = emb.getValue().getEmbeddings();
		assertEquals(4, rows.size(), "one row per window");
		EmbeddingCreateRequest second = rows.get(1);
		assertEquals("voiceprint", second.getType());
		assertEquals("voiceprint", second.getNodeKind());
		assertEquals(VoiceprintFixtures.MODEL, second.getModel());
		assertEquals("voiceprint/1:" + VoiceprintFixtures.MODEL, second.getProducerVersion());
		assertEquals(1, second.getSubjectIndex());
		assertEquals(0, second.getFrameNumber());
		assertEquals(2_500L, second.getTimeFrom());
		assertEquals(5_500L, second.getTimeTo());
		assertEquals(3, second.getDimensions());
		assertEquals(Boolean.TRUE, second.getNormalized());
		assertNull(second.getDetectionUuid(), "an audio window points at no detection");

		ArgumentCaptor<ClusterBulkCreateRequest> cl = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(loom).bulkCreateAssetClusters(eq(assetUuid), cl.capture());
		List<ClusterCreateItem> clusters = cl.getValue().getClusters();
		assertEquals(2, clusters.size());
		ClusterCreateItem first = clusters.get(0);
		assertEquals("speaker", first.getType());
		assertEquals(0, first.getClusterIndex());
		assertEquals(VoiceprintFixtures.MODEL, first.getModel());
		assertEquals(3, first.getCentroid().length);
		assertEquals(6_000L, first.getMeta().getLong("totalMs"));
		assertEquals(2, first.getMembers().size());
		assertEquals(embeddingUuids.get(0).toString(), first.getMembers().get(0).getEmbeddingUuid());
		assertEquals(embeddingUuids.get(3).toString(), clusters.get(1).getMembers().get(1).getEmbeddingUuid());
		assertTrue(first.getMembers().get(0).getConfidence() > 0.98f, "member confidence is the window's cosine to the speaker");

		verify(loom).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "voiceprint".equals(r.getNodeKind())
			&& "voiceprint".equals(r.getSchemaType()) && "".equals(r.getVariant())
			&& r.getData().getInteger("speakerCount") == 2 && !r.getData().encode().contains("vector")));
		verify(loom).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "voiceprint".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState()) && "COMPUTED".equals(r.getOrigin())
			&& ("voiceprint/1:" + VoiceprintFixtures.MODEL).equals(r.getProducerVersion())
			&& r.getResultRef() != null && "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testSpeakerOnlyModeWritesOneEmbeddingPerSpeakerAndNoClusters() throws Exception {
		assertThat(node(new VoiceprintNodeOptions().setPersistWindows(false)).process(NodeContext.create(media))).isSuccess();
		ArgumentCaptor<EmbeddingBulkCreateRequest> emb = ArgumentCaptor.forClass(EmbeddingBulkCreateRequest.class);
		verify(loom).bulkCreateAssetEmbeddings(eq(assetUuid), emb.capture());
		List<EmbeddingCreateRequest> rows = emb.getValue().getEmbeddings();
		assertEquals(2, rows.size());
		assertEquals(7_000L, rows.get(1).getTimeFrom());
		assertEquals(10_000L, rows.get(1).getTimeTo());
		assertEquals(0.88f, rows.get(1).getConfidence(), 1e-6);
		verify(loom, never()).bulkCreateAssetClusters(any(UUID.class), any());
	}

	@Test
	void testSidecarFailureRecordsFailedAndWritesNothing() throws Exception {
		when(sidecar.analyze(any(), any(), anyLong())).thenThrow(new RuntimeException("sidecar down"));
		assertThat(node(new VoiceprintNodeOptions()).process(NodeContext.create(media))).isFailed();
		verify(loom, never()).bulkCreateAssetEmbeddings(any(UUID.class), any());
		verify(loom, never()).createAssetJsonComp(any(), any());
		verify(loom).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "sidecar down".equals(r.getReason())));
	}

	@Test
	void testLoomFailureRecordsFailedLedger() throws Exception {
		// doThrow, not when(...): when() would invoke the answer installed in setup with null arguments.
		doThrow(new RuntimeException("loom unreachable")).when(loom).bulkCreateAssetEmbeddings(any(UUID.class), any());
		// Persistence is best-effort: the node still succeeds for the pipeline, but the ledger records the failure.
		node(new VoiceprintNodeOptions()).process(NodeContext.create(media));
		verify(loom).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	@Test
	void testSilenceRecordsSkippedAndWritesNothing() throws Exception {
		when(sidecar.analyze(any(), any(), anyLong())).thenReturn(VoiceprintFixtures.silent());
		assertThat(node(new VoiceprintNodeOptions()).process(NodeContext.create(media))).isSkipped();
		verify(loom, never()).bulkCreateAssetEmbeddings(any(UUID.class), any());
		verify(loom).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "SKIPPED".equals(r.getState()) && "no speech found".equals(r.getReason())));
	}
}
