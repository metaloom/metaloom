package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;
import static io.metaloom.cortex.media.hash.HashMedia.HASH;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.hash.HashMedia;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA256;

public class SHA256Node extends AbstractMediaNode<Void, HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SHA256Node.class);

	@Inject
	public SHA256Node(@Nullable LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "sha256";
	}

	@Override
	protected boolean isProcessed(NodeContext<LoomMedia> ctx) {
		return ctx.media(HASH).hasSHA256();
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isSHA256()) {
			return true;
		} else {
			// TODO log or return reason
			return false;
		}
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		HashMedia media = ctx.media().of(HASH);
		if (asset != null && asset.getHashes().getSHA256() != null) {
			media.setSHA256(asset.getHashes().getSHA256());
			return ctx.origin(REMOTE).next();
		} else {
			SHA256 hash = HashUtils.computeSHA256(media.file());
			media.setSHA256(hash);
			return ctx.origin(COMPUTED).next();
		}
	}

}
