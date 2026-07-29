package io.metaloom.loom.cortex.node.consistency;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.consistency.ConsistencyNode;
import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;

public class ConsistencyNodeTest extends AbstractMediaTest {

	@Test
	public void testSkipAction() throws IOException, LoomClientException {
		ConsistencyNode node = mockNode();
		LoomMedia media = createEmptyLoomMedia();
		NodeResult result = node.process(media);
		assertThat(result).isSkipped();
	}

	@Test
	public void testProcessVideo() throws IOException, LoomClientException {
		ConsistencyNode node = mockNode();
		LoomMedia media = mediaVideo1();
		NodeResult result = node.process(media);
		assertThat(result).isSuccess();
		// scalar/integer is widened to Long at the port boundary, so the value is a Long
		// rather than whatever width the node happened to compute in.
		assertEquals(0L, result.get(ConsistencyNode.OUT_ZERO_CHUNK_COUNT),
			"Zero chunk count should be 0 for a consistent file");
		assertEquals(Boolean.TRUE, result.get(ConsistencyNode.OUT_IS_COMPLETE),
			"File should be complete");
		assertNotNull(media.getSHA512(), "SHA512 should have been computed");
	}

	private ConsistencyNode mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		ConsistencyNode node = new ConsistencyNode(client, null, new ConsistencyNodeOptions());
		return node;
	}
}
