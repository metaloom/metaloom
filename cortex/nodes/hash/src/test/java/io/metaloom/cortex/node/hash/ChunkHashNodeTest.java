package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.media.hash.HashMedia.CHUNK_HASH_KEY;
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

public class ChunkHashNodeTest extends AbstractBasicNodeTest<ChunkHashNode> {

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(CHUNK_HASH_KEY, sampleVideoChunkHash());

		NodeResult result2 = node().process(ctx(media));
		assertThat(result2).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(CHUNK_HASH_KEY, sampleVideoChunkHash());
	}

	@Override
	public ChunkHashNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isMD5()).thenReturn(true);
		return new ChunkHashNode(client, cortexOptions, options);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, ChunkHashNode nodeMock) {
		assertThat(media).hasXAttr(2).hasXAttr(CHUNK_HASH_KEY, testMedia.chunkHash());
	}

	@Override
	protected void disableNode(ChunkHashNode nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isChunkHash()).thenReturn(false);
	}

}
