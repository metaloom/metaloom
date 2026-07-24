package io.metaloom.cortex.node.captioning;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class CaptioningNodeTest extends AbstractBasicNodeTest<CaptioningNode> {

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, CaptioningNode nodeMock) {
		assertThat(result).hasOutput(CaptioningNode.OUTPUT_CAPTION);
	}

	@Override
	protected void disableNode(CaptioningNode nodeMock) {
		CaptioningNodeOptions options = nodeMock.options();
		options.setEnabled(false);
	}

	@Override
	public CaptioningNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setEnabled(true);
		return new CaptioningNode(null, cortexOptions, options, new SmolVLMClient("localhost", 8080));
	}

}
