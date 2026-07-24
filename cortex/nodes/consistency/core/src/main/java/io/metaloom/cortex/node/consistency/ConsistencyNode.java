package io.metaloom.cortex.node.consistency;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.utils.hash.partial.PartialFile;

public class ConsistencyNode extends AbstractMediaNode<ConsistencyNodeOptions> {

	public static final NodeOutputKey<Long> OUTPUT_ZERO_CHUNK_COUNT = NodeOutputKey.of("zero_chunk_count", Long.class);
	public static final NodeOutputKey<Boolean> OUTPUT_IS_COMPLETE = NodeOutputKey.of("is_complete", Boolean.class);
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
		return media.isVideo() || media.isAudio();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();

		if (asset == null) {
			long count = computeZeroChunks(media);
			ctx.output(OUTPUT_ZERO_CHUNK_COUNT, count);
			ctx.output(OUTPUT_IS_COMPLETE, count == 0);
			return ctx.origin(COMPUTED).next();
		} else {
			Long dbCount = asset.getConsistency() != null ? asset.getConsistency().getZeroChunkCount() : null;
			if (dbCount != null) {
				ctx.output(OUTPUT_ZERO_CHUNK_COUNT, dbCount);
				ctx.output(OUTPUT_IS_COMPLETE, dbCount == 0);
				return ctx.origin(REMOTE).next();
			} else {
				long count = computeZeroChunks(media);
				ctx.output(OUTPUT_ZERO_CHUNK_COUNT, count);
				ctx.output(OUTPUT_IS_COMPLETE, count == 0);
				persist(ctx, asset, count);
				return ctx.origin(COMPUTED).next();
			}
		}
	}

	/**
	 * Persist the computed zero-chunk count onto the asset's consistency block and record a ledger entry. Best-effort and a no-op when the asset is not
	 * yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, long zeroChunkCount) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			client().updateAsset(asset.getUuid(),
				new AssetUpdateRequest().setConsistency(new ConsistencyInfo().setZeroChunkCount(zeroChunkCount))).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
		} catch (Exception e) {
			log.warn("Failed to persist consistency for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

	private long computeZeroChunks(LoomMedia media) throws NoSuchAlgorithmException, IOException {
		PartialFile pf = new PartialFile(media.path());
		return pf.computeZeroChunkCount();
	}

}
