package io.metaloom.loom.cortex.dedup;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyFloat;
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

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.dedup.FingerprintDedupDiscoverOptions;
import io.metaloom.cortex.node.dedup.FingerprintDedupNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.FingerprintInfo;
import io.metaloom.loom.rest.model.dedup.DedupGroupCreateRequest;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Unit tests for the fingerprint-dedup discovery node: it must build the correct KEEP/DUP split from similarity hits, report a PENDING group, and
 * apply the size/completeness safeguards - all without moving any file.
 */
class FingerprintDedupNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID queryUuid = UUID.randomUUID();
	private final UUID hitUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private CortexOptions cortexOptions;
	private StubLoomMedia media;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		// A ledger write always succeeds.
		LoomClientRequest<NodeResultResponse> ledger = mock(LoomClientRequest.class);
		when(ledger.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledger);

		File videoFile = new File(tempDir, "clip.mp4");
		videoFile.createNewFile();
		media = new StubLoomMedia(videoFile.getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);
	}

	private FingerprintDedupNode node() {
		return new FingerprintDedupNode(client, cortexOptions, new FingerprintDedupDiscoverOptions());
	}

	private static AssetResponse asset(UUID uuid, long size, Long zeroChunk) {
		return new AssetResponse()
			.setUuid(uuid)
			.setFile(new FileInfo().setSize(size))
			.setConsistency(new ConsistencyInfo().setZeroChunkCount(zeroChunk));
	}

	@SuppressWarnings("unchecked")
	private <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> LoomClientRequest<T> request(T body) throws Exception {
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
		return req;
	}

	@Test
	@SuppressWarnings("unchecked")
	void testReportsGroupWithLargerKeep() throws Exception {
		// Build all request mocks first; nesting a stubbed mock inside another when() confuses Mockito.
		AssetResponse query = asset(queryUuid, 2000L, 0L).setFingerprint(new FingerprintInfo().setFingerprintV1("deadbeef"));
		LoomClientRequest<AssetResponse> queryReq = request(query);
		LoomClientRequest<AssetResponse> hitReq = request(asset(hitUuid, 1000L, 0L));
		SimilarAssetListResponse hits = new SimilarAssetListResponse();
		hits.add(new SimilarAssetResponse().setAssetUuid(hitUuid.toString()).setScore(0.9f).setSha512("hitsha"));
		LoomClientRequest<SimilarAssetListResponse> hitsReq = request(hits);
		LoomClientRequest<DedupGroupResponse> groupReq = request(new DedupGroupResponse().setUuid(UUID.randomUUID().toString()));

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(queryReq);
		when(client.loadAsset(eq(hitUuid))).thenReturn(hitReq);
		when(client.listSimilarAssets(eq(queryUuid), any(), anyInt(), anyFloat())).thenReturn(hitsReq);
		when(client.createDedupGroup(any())).thenReturn(groupReq);

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		// KEEP must be the larger (query, 2000), the smaller hit (1000) must be the DUP.
		verify(client).createDedupGroup(org.mockito.ArgumentMatchers.argThat((DedupGroupCreateRequest r) -> {
			boolean keepOk = queryUuid.toString().equals(r.getKeepAssetUuid());
			boolean keepMember = r.getMembers().stream()
				.anyMatch(m -> DedupGroupMemberModel.ROLE_KEEP.equals(m.getRole()) && queryUuid.toString().equals(m.getAssetUuid()));
			boolean dupMember = r.getMembers().stream()
				.anyMatch(m -> DedupGroupMemberModel.ROLE_DUP.equals(m.getRole()) && hitUuid.toString().equals(m.getAssetUuid()));
			return keepOk && keepMember && dupMember;
		}));
	}

	/**
	 * Loom answers a re-proposal of an already-decided candidate set with the decision instead of creating a new group. That is a no-op, and reporting
	 * it as a discovery would put a misleading SUCCESS row in the ledger on every run over an already-reviewed corpus.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void testSkipsWhenTheCandidateSetWasAlreadyDecided() throws Exception {
		AssetResponse query = asset(queryUuid, 2000L, 0L).setFingerprint(new FingerprintInfo().setFingerprintV1("deadbeef"));
		LoomClientRequest<AssetResponse> queryReq = request(query);
		LoomClientRequest<AssetResponse> hitReq = request(asset(hitUuid, 1000L, 0L));
		SimilarAssetListResponse hits = new SimilarAssetListResponse();
		hits.add(new SimilarAssetResponse().setAssetUuid(hitUuid.toString()).setScore(0.9f).setSha512("hitsha"));
		LoomClientRequest<SimilarAssetListResponse> hitsReq = request(hits);
		LoomClientRequest<DedupGroupResponse> groupReq = request(new DedupGroupResponse()
			.setUuid(UUID.randomUUID().toString())
			.setStatus(DedupGroupResponse.STATUS_REJECTED));

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(queryReq);
		when(client.loadAsset(eq(hitUuid))).thenReturn(hitReq);
		when(client.listSimilarAssets(eq(queryUuid), any(), anyInt(), anyFloat())).thenReturn(hitsReq);
		when(client.createDedupGroup(any())).thenReturn(groupReq);

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		verify(client, never()).createAssetNodeResult(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void testAbortsWhenDuplicateLargerThanKeep() throws Exception {
		// The KEEP can only be a COMPLETE asset. Here the larger hit (5000) is INCOMPLETE, so the smaller complete query (2000) is the KEEP - and
		// the larger incomplete hit as a DUP trips abortOnLargerDup (never discard the larger/more-complete file).
		AssetResponse query = asset(queryUuid, 2000L, 0L).setFingerprint(new FingerprintInfo().setFingerprintV1("deadbeef"));
		LoomClientRequest<AssetResponse> queryReq = request(query);
		LoomClientRequest<AssetResponse> hitReq = request(asset(hitUuid, 5000L, 3L));
		SimilarAssetListResponse hits = new SimilarAssetListResponse();
		hits.add(new SimilarAssetResponse().setAssetUuid(hitUuid.toString()).setScore(0.9f).setSha512("hitsha"));
		LoomClientRequest<SimilarAssetListResponse> hitsReq = request(hits);

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(queryReq);
		when(client.loadAsset(eq(hitUuid))).thenReturn(hitReq);
		when(client.listSimilarAssets(eq(queryUuid), any(), anyInt(), anyFloat())).thenReturn(hitsReq);

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		verify(client, never()).createDedupGroup(any());
	}

	/**
	 * An unreachable similarity index is a failure, not "no duplicates found".
	 *
	 * <p>
	 * Discovery reported SUCCESS on both of its failure paths until 2026-08-18, because it ended them
	 * with {@code ctx.failure(cause).next()} and {@code NodeContextImpl.next()} read only the skip
	 * reason. A dedup proposal that never appeared because the query broke looked exactly like a corpus
	 * with no duplicates in it.
	 * </p>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void testFailsWhenTheSimilarityQueryThrows() throws Exception {
		// Build the request mock first; nesting a stubbed mock inside another when() confuses Mockito.
		AssetResponse query = asset(queryUuid, 2000L, 0L).setFingerprint(new FingerprintInfo().setFingerprintV1("deadbeef"));
		LoomClientRequest<AssetResponse> queryReq = request(query);

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(queryReq);
		when(client.listSimilarAssets(eq(queryUuid), any(), anyInt(), anyFloat())).thenThrow(new RuntimeException("index unreachable"));

		assertThat(node().process(NodeContext.create(media)))
			.isFailed()
			.hasMessageContaining("index unreachable");

		verify(client, never()).createDedupGroup(any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void testFailsWhenTheGroupCannotBeReported() throws Exception {
		AssetResponse query = asset(queryUuid, 2000L, 0L).setFingerprint(new FingerprintInfo().setFingerprintV1("deadbeef"));
		LoomClientRequest<AssetResponse> queryReq = request(query);
		LoomClientRequest<AssetResponse> hitReq = request(asset(hitUuid, 1000L, 0L));
		SimilarAssetListResponse hits = new SimilarAssetListResponse();
		hits.add(new SimilarAssetResponse().setAssetUuid(hitUuid.toString()).setScore(0.9f).setSha512("hitsha"));
		LoomClientRequest<SimilarAssetListResponse> hitsReq = request(hits);

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(queryReq);
		when(client.loadAsset(eq(hitUuid))).thenReturn(hitReq);
		when(client.listSimilarAssets(eq(queryUuid), any(), anyInt(), anyFloat())).thenReturn(hitsReq);
		when(client.createDedupGroup(any())).thenThrow(new RuntimeException("loom unreachable"));

		// The cause now names what actually broke - the old message was the bare "failed to report dedup
		// group", which said nothing an operator could act on even once it stopped being discarded.
		assertThat(node().process(NodeContext.create(media)))
			.isFailed()
			.hasMessageContaining("loom unreachable");

		verify(client).createAssetNodeResult(any(), any());
	}
}
