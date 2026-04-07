package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;
import static io.metaloom.cortex.media.hash.HashMedia.HASH;

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
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.HashUtils;

public class ChunkHashNode extends AbstractMediaNode<Void, HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ChunkHashNode.class);

	@Inject
	public ChunkHashNode(LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "chunk-hash";
	}

	@Override
	protected boolean isProcessed(NodeContext<LoomMedia> ctx) {
		return ctx.media(HASH).hasChunkHash();
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isChunkHash()) {
			return true;
		} else {
			return false;
		}
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		HashMedia media = ctx.media(HASH);
		if (asset != null && asset.getHashes().getChunkHash() != null) {
			media.setChunkHash(asset.getHashes().getChunkHash());
			return ctx.origin(REMOTE).next();
		} else {
			ChunkHash hash = HashUtils.computeChunkHash(media.file());
			media.setChunkHash(hash);
			return ctx.origin(COMPUTED).next();
		}
	}

}
