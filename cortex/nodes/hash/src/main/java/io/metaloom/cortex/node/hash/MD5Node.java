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
import io.metaloom.utils.hash.MD5;

public class MD5Node extends AbstractMediaNode<Void, HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(MD5Node.class);

	public static final String OUTPUT_MD5 = "md5";

	@Inject
	public MD5Node(@Nullable LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "md5";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isMD5()) {
			return true;
		} else {
			log.debug("[{}] MD5 not enabled in hash options", this);
			return false;
		}
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (asset != null && asset.getHashes().getMD5() != null) {
			ctx.output(OUTPUT_MD5, asset.getHashes().getMD5().toString());
			return ctx.origin(REMOTE).next();
		} else {
			MD5 hash = HashUtils.computeMD5(ctx.media().file());
			ctx.output(OUTPUT_MD5, hash.toString());
			return ctx.origin(COMPUTED).next();
		}
	}

}
