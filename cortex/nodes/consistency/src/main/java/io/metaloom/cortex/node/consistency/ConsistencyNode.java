package io.metaloom.cortex.node.consistency;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;
import static io.metaloom.cortex.media.consistency.ConsistencyMedia.CONSISTENCY;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.consistency.ConsistencyMedia;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.partial.PartialFile;

public class ConsistencyNode extends AbstractMediaNode<Void, ConsistencyNodeOptions> {

	@Inject
	public ConsistencyNode(@Nullable LoomClient client, CortexOptions cortexOption, ConsistencyNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "consistency";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		// ctx.skipped("no video or audio media").next();
		return media.isVideo() && media.isAudio();
	}

	@Override
	protected boolean isProcessed(NodeContext<LoomMedia> ctx) {
		ConsistencyMedia media = ctx.media(CONSISTENCY);
		return media.hasZeroChunkCount();
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		ConsistencyMedia media = ctx.media(CONSISTENCY);

		if (asset == null) {
			// if (!isOfflineMode()) {
			computeSum(media);
			return ctx.origin(COMPUTED).next();

		} else {
			Long dbCount = asset.getConsistency().getZeroChunkCount();
			if (dbCount != null) {
				media.setZeroChunkCount(dbCount);
				return ctx.origin(REMOTE).next();
			} else {
				computeSum(media);
				return ctx.origin(COMPUTED).next();
			}
		}

	}

	private void computeSum(ConsistencyMedia media) throws NoSuchAlgorithmException, IOException {
		PartialFile pf = new PartialFile(media.path());
		long count = pf.computeZeroChunkCount();
		media.setZeroChunkCount(count);
	}

}
