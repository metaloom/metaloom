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

		return mock;
	}

}
