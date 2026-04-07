package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

public class SHA512Node extends AbstractMediaNode<Void, HashNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SHA512Node.class);

	public static final String OUTPUT_SHA512 = "sha512";

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
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		SHA512 hash = HashUtils.computeSHA512(media.file());
		media.setSHA512(hash);
		ctx.output(OUTPUT_SHA512, hash.toString());
		return ctx.origin(COMPUTED).next();
	}

}
