package io.metaloom.cortex.common.node.dummy;

import java.io.IOException;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractFilesystemNode;
import io.metaloom.loom.client.common.LoomClient;

public class DummyNode extends AbstractFilesystemNode<LoomMedia, DummyOptions> {

	boolean invoked = false;

	public DummyNode(LoomClient client, CortexOptions cortexOption, DummyOptions option) {
		super(client, cortexOption, option);
	}

	@Override
	public NodeResult process(NodeContext<LoomMedia> ctx) throws IOException {
		invoked = true;
		return ctx.skipped("not implemented").next();
	}

	@Override
	public String name() {
		return "dummy";
	}

	public boolean wasInvoked() {
		return invoked;
	}
}
