package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentEntry;
import io.metaloom.video4j.VideoFile;

public class SceneDetectionNode extends AbstractMediaNode<SceneDetectionOptions> {

	public static final Logger log = LoggerFactory.getLogger(SceneDetectionNode.class);

	public static final NodeOutputKey<String> OUTPUT_SCENE_DETECTION = NodeOutputKey.of("scene_detection", String.class);

	/** In-heap skip cache of the scene-detection output, keyed by media path, to avoid re-running optical-flow detection within this worker's lifetime.
	 * Non-durable - the durable copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(50_000);

	private OpticalFlowSceneDetector detector = new OpticalFlowSceneDetector();

	@Inject
	public SceneDetectionNode(@Nullable LoomClient client, CortexOptions cortexOptions, SceneDetectionOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "scene-detection";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isEnabled()) {
			return ctx.media().isVideo();
		} else {
			return false;
		}
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo()) {
			String path = media.absolutePath();
			String cached = resultCache.get(path);
			if (cached != null) {
				ctx.output(OUTPUT_SCENE_DETECTION, cached);
				return ctx.origin(LOCAL).next();
			}
			VideoFile video = VideoFile.open(media.path());
			SceneDetectionResult result = detector.detect(video);
			ctx.output(OUTPUT_SCENE_DETECTION, result.toString());
			resultCache.put(path, result.toString());
			persist(ctx, asset, result);
			return ctx.origin(COMPUTED).next();
		} else {
			return ctx.skipped("no video media").next();
		}
	}

	/**
	 * Persist the detected scenes as the whole {@code SCENE} segment set of {@code asset_segment_comp} and record a ledger entry. The batch replace
	 * deletes any surplus scenes from a previous, longer run. Best-effort and a no-op when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, SceneDetectionResult result) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			List<SegmentEntry> entries = new ArrayList<>();
			int seq = 0;
			for (Scene scene : result.scenes()) {
				entries.add(new SegmentEntry().setSeq(seq++).setTimeFrom(scene.getFrom()).setTimeTo(scene.getTo()));
			}
			SegmentCompCreateRequest request = new SegmentCompCreateRequest();
			request.setNodeKind(name());
			request.setSegmentType("SCENE");
			request.setSegments(entries);
			client().createAssetSegmentComps(asset.getUuid(), request).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_segment_comp"));
		} catch (Exception e) {
			log.warn("Failed to persist scenes for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
