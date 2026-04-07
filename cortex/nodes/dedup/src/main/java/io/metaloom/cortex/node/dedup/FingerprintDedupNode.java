package io.metaloom.cortex.node.dedup;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class FingerprintDedupNode extends AbstractMediaNode<Void, DedupNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintDedupNode.class);

	@Inject
	public FingerprintDedupNode(@Nullable LoomClient client, CortexOptions cortexOptions, DedupNodeOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "fingerprint-dedup";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isVideo();
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		return ctx.skipped("not implemented").next();
	}

}
