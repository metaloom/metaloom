package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.media.hash.HashMedia.MD5_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class MD5NodeTest extends AbstractBasicNodeTest<MD5Node> {

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, MD5Node nodeMock) {
		assertThat(media).hasXAttr(2).hasXAttr(MD5_KEY, testMedia.md5());
	}

	@Test
	@Override
	public void testProcessImage() throws IOException {
		super.testProcessImage();
	}

	@Override
	public MD5Node mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isMD5()).thenReturn(true);
		return new MD5Node(client, cortexOptions, options);
	}

	@Override
	protected void disableNode(MD5Node nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isMD5()).thenReturn(false);
	}

}
