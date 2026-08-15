package io.metaloom.cortex.common.node.media;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.common.LoomClientResponse;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.utils.hash.SHA512;

public class LoomClientMock {

	// @SuppressWarnings("unchecked")
	// public static LoomGRPCClient mockGrpcClient() {
	// LoomGRPCClient mock = mock(LoomGRPCClient.class);
	// LoomClientRequest<AssetResponse> req = mock(LoomClientRequest.class);
	// AssetResponse response = mock(AssetResponse.class);
	// when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
	// when(mock.loadAsset(any())).thenReturn(req);
	// return mock;
	// }

	@SuppressWarnings("unchecked")
	public static LoomClient mockClient() throws LoomClientException {
		LoomHttpClient mock = mock(LoomHttpClient.class);
		LoomClientRequest<AssetResponse> req = mock(LoomClientRequest.class);
		AssetResponse response = mock(AssetResponse.class);
		// A real uuid, because every write-back call keys on it. Left unstubbed it answered null, which the
		// nodes then passed to the client as the asset to write against.
		when(response.getUuid()).thenReturn(UUID.randomUUID());
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
//		AssetId id = any();
		SHA512 sha512 = any();
		when(mock.loadAsset(sha512)).thenReturn(req);
		//when(mock.loadAsset(id)).thenReturn(req);

		// Stub the node result persistence path so nodes that write back to Loom (transcript payload + asset_node_result ledger) can be tested
		// with a client instead of forced offline mode.
		TranscriptResponse transcriptResponse = new TranscriptResponse().setUuid(UUID.randomUUID());
		LoomClientRequest<TranscriptResponse> transcriptReq = mock(LoomClientRequest.class);
		when(transcriptReq.sync()).thenReturn(new LoomClientResponseImpl<>(transcriptResponse, 201, "Created", Map.of()));
		when(mock.createAssetTranscript(any(), any())).thenReturn(transcriptReq);

		NodeResultResponse nodeResultResponse = new NodeResultResponse().setUuid(UUID.randomUUID());
		LoomClientRequest<NodeResultResponse> nodeResultReq = mock(LoomClientRequest.class);
		when(nodeResultReq.sync()).thenReturn(new LoomClientResponseImpl<>(nodeResultResponse, 201, "Created", Map.of()));
		when(mock.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);

		stubBulkWrites(mock);

		return mock;
	}

	/**
	 * Stub the bulk write-back calls a detection producing node makes.
	 *
	 * <p>
	 * These were previously unstubbed, so every one of them returned null and blew up inside the node. That went unnoticed because the node caught the
	 * failure and reported SUCCESS regardless - the tests asserted a green result over a write path that had never once worked. The responses echo one
	 * row per requested item, because the node matches stored rows against what it sent positionally and quietly skips embeddings and crops when the
	 * counts disagree.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	private static void stubBulkWrites(LoomHttpClient mock) throws LoomClientException {
		when(mock.bulkCreateAssetDetections(any(UUID.class), any())).thenAnswer(invocation -> {
			DetectionBulkCreateRequest request = invocation.getArgument(1);
			DetectionBulkResponse response = new DetectionBulkResponse();
			request.getDetections().forEach(item -> response.getDetections().add(new DetectionResponse().setUuid(UUID.randomUUID())));
			response.setTotal(response.getDetections().size()).setCreated(response.getDetections().size());
			LoomClientRequest<DetectionBulkResponse> req = mock(LoomClientRequest.class);
			when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 201, "Created", Map.of()));
			return req;
		});

		when(mock.bulkCreateAssetEmbeddings(any(UUID.class), any())).thenAnswer(invocation -> {
			EmbeddingBulkCreateRequest request = invocation.getArgument(1);
			EmbeddingBulkResponse response = new EmbeddingBulkResponse();
			request.getEmbeddings().forEach(item -> response.getEmbeddings().add(new EmbeddingResponse().setUuid(UUID.randomUUID())));
			response.setTotal(response.getEmbeddings().size()).setCreated(response.getEmbeddings().size());
			LoomClientRequest<EmbeddingBulkResponse> req = mock(LoomClientRequest.class);
			when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 201, "Created", Map.of()));
			return req;
		});

		when(mock.bulkCreateAssetClusters(any(UUID.class), any())).thenAnswer(invocation -> {
			ClusterBulkCreateRequest request = invocation.getArgument(1);
			ClusterBulkResponse response = new ClusterBulkResponse();
			request.getClusters().forEach(item -> response.getClusters().add(new ClusterResponse().setUuid(UUID.randomUUID())));
			response.setTotal(response.getClusters().size()).setCreated(response.getClusters().size());
			LoomClientRequest<ClusterBulkResponse> req = mock(LoomClientRequest.class);
			when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 201, "Created", Map.of()));
			return req;
		});

		LoomClientRequest<AttachmentResponse> cropReq = mock(LoomClientRequest.class);
		when(cropReq.sync()).thenReturn(new LoomClientResponseImpl<>(new AttachmentResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(mock.uploadFaceCrop(any(), any(UUID.class), any(UUID.class), any(), any())).thenReturn(cropReq);
	}

}
