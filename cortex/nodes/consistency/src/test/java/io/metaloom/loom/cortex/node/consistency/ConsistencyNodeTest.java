package io.metaloom.loom.cortex.node.consistency;

import static io.metaloom.cortex.api.media.LoomMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.consistency.ConsistencyNode;
import io.metaloom.cortex.api.node.NodeResult;
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
		assertThat(media).hasSHA512();
	}

	@Test
	public void testProcessVideo() throws IOException, LoomClientException {
		ConsistencyNode node = mockNode();
		LoomMedia media = mediaVideo1();
		NodeResult result = node.process(media);
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(SHA_512_KEY, data.sampleVideoSHA512());
		assertThat(media).isConsistent();
		assertThat(media).hasXAttr(2);
	}

	private ConsistencyNode mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		ConsistencyNode node = new ConsistencyNode(client, null, null);
		return node;
	}
}
