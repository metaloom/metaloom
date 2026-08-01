package io.metaloom.cortex.node.hash;

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
		assertThat(result).hasOutput(ChunkHashNode.OUT_HASH, sampleVideoChunkHash().toString());

		NodeResult result2 = node().process(ctx(media));
		assertThat(result2).isSuccess();
		assertThat(result2).hasOutput(ChunkHashNode.OUT_HASH, sampleVideoChunkHash().toString());
	}

	@Override
	public ChunkHashNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		// Unstubbed, isEnabled() answers false and process() short-circuits to SKIPPED.
		when(options.isEnabled()).thenReturn(true);
		// ChunkHashNode#isProcessable reads isChunkHash, not isMD5 - stubbing the latter
		// left the node permanently unprocessable while looking configured.
		when(options.isChunkHash()).thenReturn(true);
		return new ChunkHashNode(client, cortexOptions, options);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, ChunkHashNode nodeMock) {
		assertThat(result).hasOutput(ChunkHashNode.OUT_HASH, testMedia.chunkHash().toString());
	}

	@Override
	protected void disableNode(ChunkHashNode nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isChunkHash()).thenReturn(false);
	}

}
