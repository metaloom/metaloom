package io.metaloom.cortex.media.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

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
import io.metaloom.loom.rest.model.asset.AssetResponse;

public abstract class AbstractNodeTest<T extends FilesystemNode<?, ?, ?>> extends AbstractMediaTest {

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
		when(request.sync()).thenReturn(response);

		AssetId id = any();
		when(client.loadAsset(id)).thenReturn(request);
		return client;
	}

	public T node() {
		return node;
	}
}
