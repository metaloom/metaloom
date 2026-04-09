package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.test.data.TestMedia;

public class SHA256NodeTest extends AbstractBasicNodeTest<SHA256Node> {

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(ctx(media));
		assertThat(result).isSuccess()
			.hasOutput(SHA256Node.OUTPUT_SHA256, sampleVideoSHA256().toString());
	}

	@Test
	@Override
	public void testDisabled() throws IOException {
		super.testDisabled();
	}

	@Test
	@Override
	public void testProcessDoc() throws IOException {
		super.testProcessDoc();
	}

	@Test
	public void testPullFromLoom() throws LoomClientException {
		// Mock the client
		AssetResponse response = mock(AssetResponse.class);
		LoomClient clientMock = mockClient(response);
		CortexOptions cortexOptions = null;

		// Invoke the action
		LoomMedia media = mediaVideo1();
		NodeResult result = mockNode(clientMock, cortexOptions).process(ctx(media));

		assertThat(result).isSuccess()
			.hasOutput(SHA256Node.OUTPUT_SHA256, sampleVideoSHA256().toString());

		// Verify that the db object was accessed
		AssetId id = any();
		verify(clientMock, times(1)).loadAsset(id);
		verify(response, times(1)).getHashes().getSHA256();
	}

	@Override
	public SHA256Node mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isSHA256()).thenReturn(true);
		return new SHA256Node(client, cortexOptions, options);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, SHA256Node nodeMock) {
		assertThat(result).hasOutput(SHA256Node.OUTPUT_SHA256, testMedia.sha256().toString());
	}

	@Override
	protected void disableNode(SHA256Node nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isSHA256()).thenReturn(false);
	}

}
