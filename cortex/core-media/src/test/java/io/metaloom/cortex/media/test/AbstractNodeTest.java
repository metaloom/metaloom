package io.metaloom.cortex.media.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;

import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.SHA512;

public abstract class AbstractNodeTest<T extends FilesystemNode<?, ?>> extends AbstractMediaTest {

	private T node;

	@BeforeEach
	public void setup() throws IOException, LoomClientException {
		node = mockNode();
	}

	public abstract T mockNode(LoomClient client, CortexOptions cortexOptions);

	public T mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		return mockNode(client, options());
	}

	public LoomClient mockClient(AssetResponse response) throws LoomClientException {
		LoomHttpClient client = mock(LoomHttpClient.class);
		LoomClientRequest<AssetResponse> request = mock(LoomClientRequest.class);
		when(request.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));

		AssetId id = any();
		when(client.loadAsset(id)).thenReturn(request);
		// loadAsset(SHA512) is a default method that would delegate to loadAsset(AssetId),
		// but a mock stubs defaults too and answers null. Nodes look an asset up by its
		// SHA-512, so without this the lookup returned null, the node fell through to
		// computing, and any test meaning to exercise the "already known to Loom" path
		// silently exercised the local one instead.
		SHA512 hash = any();
		when(client.loadAsset(hash)).thenReturn(request);
		return client;
	}

	public T node() {
		return node;
	}
}
