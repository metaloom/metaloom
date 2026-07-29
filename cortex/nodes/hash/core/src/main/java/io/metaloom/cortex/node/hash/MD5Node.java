package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.MD5;

public class MD5Node extends AbstractMediaNode<HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(MD5Node.class);

	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	public static final OutputPort<String> OUT_HASH = OutputPort.one("hash", ContentTypeRegistry.HASH_MD5, String.class);

	/** In-heap skip cache of computed hashes, keyed by media path, to avoid re-reading a file within this worker's lifetime. Non-durable - the durable
	 * copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(100_000);

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
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (asset != null && asset.getHashes().getMD5() != null) {
			String md5 = asset.getHashes().getMD5().toString();
			ctx.output(OUT_HASH, md5);
			return ctx.origin(REMOTE).next();
		}
		String path = ctx.media().absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUT_HASH, cached);
			return ctx.origin(LOCAL).next();
		}
		MD5 hash = HashUtils.computeMD5(ctx.media().file());
		ctx.output(OUT_HASH, hash.toString());
		resultCache.put(path, hash.toString());
		persist(ctx, asset, hash);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Persist the freshly computed MD5 onto the asset row and record a ledger entry. Best-effort and a no-op when the asset is not yet known to Loom or
	 * we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, MD5 hash) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			client().updateAsset(asset.getUuid(), new AssetUpdateRequest().setHashes(new HashInfo().setMD5(hash))).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
		} catch (Exception e) {
			log.warn("Failed to persist md5 for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
