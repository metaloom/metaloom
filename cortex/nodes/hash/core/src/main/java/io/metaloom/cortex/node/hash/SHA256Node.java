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
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA256;

public class SHA256Node extends AbstractMediaNode<HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SHA256Node.class);

	public static final NodeOutputKey<String> OUTPUT_SHA256 = NodeOutputKey.of("sha256", String.class);

	/** In-heap skip cache of computed hashes, keyed by media path, to avoid re-reading a file within this worker's lifetime. Non-durable - the durable
	 * copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(100_000);

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
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (asset != null && asset.getHashes().getSHA256() != null) {
			String sha256 = asset.getHashes().getSHA256().toString();
			ctx.output(OUTPUT_SHA256, sha256);
			return ctx.origin(REMOTE).next();
		}
		String path = ctx.media().absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUTPUT_SHA256, cached);
			return ctx.origin(LOCAL).next();
		}
		SHA256 hash = HashUtils.computeSHA256(ctx.media().file());
		ctx.output(OUTPUT_SHA256, hash.toString());
		resultCache.put(path, hash.toString());
		persist(ctx, asset, hash);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Persist the freshly computed SHA-256 onto the asset row and record a ledger entry. Best-effort and a no-op when the asset is not yet known to Loom
	 * or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, SHA256 hash) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			client().updateAsset(asset.getUuid(), new AssetUpdateRequest().setHashes(new HashInfo().setSHA256(hash))).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
		} catch (Exception e) {
			log.warn("Failed to persist sha256 for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
