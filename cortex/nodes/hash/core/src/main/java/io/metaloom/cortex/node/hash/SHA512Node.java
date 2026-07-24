package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

public class SHA512Node extends AbstractMediaNode<HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SHA512Node.class);

	public static final NodeOutputKey<String> OUTPUT_SHA512 = NodeOutputKey.of("sha512", String.class);

	/** In-heap skip cache of computed SHA-512 hashes, keyed by media path, to avoid re-reading a file within this worker's lifetime. Non-durable - the
	 * durable copy lives in Loom. */
	private final LocalResultCache<SHA512> resultCache = new LocalResultCache<>(100_000);

	@Inject
	public SHA512Node(@Nullable LoomClient client, CortexOptions cortexOption, HashNodeOptions options) {
		super(client, cortexOption, options);
		if (options().isSHA512()) {
			log.info("SHA512 hashing enabled");
		} else {
			log.info("SHA512 hashing disabled");
		}
	}

	@Override
	public String name() {
		return "sha512";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return options().isSHA512();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();
		SHA512 cached = resultCache.get(path);
		if (cached != null) {
			media.setSHA512(cached);
			ctx.output(OUTPUT_SHA512, cached.toString());
			return ctx.origin(LOCAL).next();
		}
		SHA512 hash = HashUtils.computeSHA512(media.file());
		media.setSHA512(hash);
		ctx.output(OUTPUT_SHA512, hash.toString());
		resultCache.put(path, hash);
		// The SHA-512 is the asset's content identity, so when the asset already exists in Loom there is nothing to write back - only record the
		// ledger marker that this node ran on the asset.
		recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
		return ctx.origin(COMPUTED).next();
	}

}
