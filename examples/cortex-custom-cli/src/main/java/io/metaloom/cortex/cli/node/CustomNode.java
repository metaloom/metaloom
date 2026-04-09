package io.metaloom.cortex.cli.node;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class CustomNode extends AbstractMediaNode<Void, CustomNodeOptions> {

	@Inject
	public CustomNode(@Nullable LoomClient client, CortexOptions cortexOption, CustomNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "custom";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return true;
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		System.out.println("Custom Action Invoked");
		return NodeResult.success(null);
	}

}
