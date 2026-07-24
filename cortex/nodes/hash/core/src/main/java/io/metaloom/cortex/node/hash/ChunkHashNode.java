package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.HashUtils;

public class ChunkHashNode extends AbstractMediaNode<HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ChunkHashNode.class);

	public static final NodeOutputKey<String> OUTPUT_CHUNK_HASH = NodeOutputKey.of("chunk_hash", String.class);

	/** In-heap skip cache of computed hashes, keyed by media path, to avoid re-reading a file within this worker's lifetime. Non-durable - the durable
	 * copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(100_000);

	@Inject
	public ChunkHashNode(@Nullable LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "chunk-hash";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return options().isChunkHash();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (asset != null && asset.getHashes().getChunkHash() != null) {
			String chunkHash = asset.getHashes().getChunkHash().toString();
			ctx.output(OUTPUT_CHUNK_HASH, chunkHash);
			return ctx.origin(REMOTE).next();
		}
		String path = ctx.media().absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUTPUT_CHUNK_HASH, cached);
			return ctx.origin(LOCAL).next();
		}
		ChunkHash hash = HashUtils.computeChunkHash(ctx.media().file());
		ctx.output(OUTPUT_CHUNK_HASH, hash.toString());
		resultCache.put(path, hash.toString());
		persist(ctx, asset, hash);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Persist the freshly computed chunk hash onto the asset row and record a ledger entry. Best-effort and a no-op when the asset is not yet known to
	 * Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, ChunkHash hash) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			client().updateAsset(asset.getUuid(), new AssetUpdateRequest().setHashes(new HashInfo().setChunkHash(hash))).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
		} catch (Exception e) {
			log.warn("Failed to persist chunk hash for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
