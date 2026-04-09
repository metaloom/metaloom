package io.metaloom.loom.node.tika;

import static io.metaloom.cortex.media.hash.HashMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.tika.TikaMedia.TIKA_FLAGS_KEY;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.cortex.node.tika.TikaNode;
import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;

public class TikaNodeTest extends AbstractMediaTest {

	@Test
	public void testAction() throws IOException, LoomClientException {
		TikaNode node = mockNode();
		LoomMedia media = mediaVideo1();
		NodeResult result = node.process(media);
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(SHA_512_KEY, TIKA_FLAGS_KEY);
	}

	public TikaNode mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		return new TikaNode(client, new CortexOptions(), new TikaNodeOptions());
	}
}
