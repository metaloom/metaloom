package io.metaloom.cortex.node.consistency;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.utils.hash.partial.PartialFile;

@NodeSpec(nodeId = "consistency", name = "Consistency Check", icon = "verified", category = NodeCategory.ANALYSIS,
	description = "Check media file integrity (zero chunk detection, completeness).", defaultConcurrency = 4)
public class ConsistencyNode extends AbstractMediaNode<ConsistencyNodeOptions> {

	@PortDoc(label = "Media", description = "The file to inspect for truncation and holes")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Zero Chunks", description = "How many all-zero chunks were found - a strong sign of a truncated or sparse copy")
	public static final OutputPort<Long> OUT_ZERO_CHUNK_COUNT = OutputPort.one("zero_chunk_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Complete", description = "False while the file still looks like it is being written")
	public static final OutputPort<Boolean> OUT_IS_COMPLETE = OutputPort.one("is_complete", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	/** In-heap skip cache of the computed zero-chunk count, keyed by media path, size and mtime, to avoid re-scanning a file within this worker's lifetime. Non-durable
	 * - the durable copy lives in Loom. */
	private final LocalResultCache<Long> resultCache = new LocalResultCache<>(50_000);

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
		String path = cacheKey(media);

		Long cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUT_ZERO_CHUNK_COUNT, cached);
			ctx.output(OUT_IS_COMPLETE, cached == 0);
			return ctx.origin(LOCAL).next();
		}

		if (asset == null) {
			long count = computeZeroChunks(media);
			ctx.output(OUT_ZERO_CHUNK_COUNT, count);
			ctx.output(OUT_IS_COMPLETE, count == 0);
			resultCache.put(path, count);
			return ctx.origin(COMPUTED).next();
		} else {
			Long dbCount = asset.getConsistency() != null ? asset.getConsistency().getZeroChunkCount() : null;
			if (dbCount != null) {
				ctx.output(OUT_ZERO_CHUNK_COUNT, dbCount);
				ctx.output(OUT_IS_COMPLETE, dbCount == 0);
				return ctx.origin(REMOTE).next();
			} else {
				long count = computeZeroChunks(media);
				ctx.output(OUT_ZERO_CHUNK_COUNT, count);
				ctx.output(OUT_IS_COMPLETE, count == 0);
				resultCache.put(path, count);
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

	/**
	 * Path + size + mtime, not the path alone.
	 *
	 * <p>
	 * 🔴 This node exists to notice a file that is still being written. Keyed on the path, the first verdict on a half-copied file - "incomplete" -
	 * was replayed for the rest of the worker's life, so the finished file was never seen as complete and every node gated on {@code is_complete}
	 * skipped it until a restart. A file that grows changes size and mtime, which changes the key.
	 * </p>
	 */
	static String cacheKey(LoomMedia media) {
		String path = media.absolutePath();
		java.io.File file = new java.io.File(path);
		return path + "|" + file.length() + "|" + file.lastModified();
	}

	private long computeZeroChunks(LoomMedia media) throws NoSuchAlgorithmException, IOException {
		PartialFile pf = new PartialFile(media.path());
		return pf.computeZeroChunkCount();
	}

}
