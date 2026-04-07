package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

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
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA256;

public class SHA256Node extends AbstractMediaNode<Void, HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SHA256Node.class);

	public static final String OUTPUT_SHA256 = "sha256";

	@Inject
	public SHA256Node(@Nullable LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "sha256";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return options().isSHA256();
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (asset != null && asset.getHashes().getSHA256() != null) {
			ctx.output(OUTPUT_SHA256, asset.getHashes().getSHA256().toString());
			return ctx.origin(REMOTE).next();
		} else {
			SHA256 hash = HashUtils.computeSHA256(ctx.media().file());
			ctx.output(OUTPUT_SHA256, hash.toString());
			return ctx.origin(COMPUTED).next();
		}
	}

}
