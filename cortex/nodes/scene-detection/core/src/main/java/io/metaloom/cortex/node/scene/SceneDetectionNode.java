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
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentEntry;
import io.metaloom.video4j.VideoFile;

@NodeSpec(nodeId = "scene-detection", name = "Scene Detection", icon = "movie_filter", category = NodeCategory.ANALYSIS,
	description = "Detect scene boundaries in video files.",
	defaultConcurrency = 2)
public class SceneDetectionNode extends AbstractMediaNode<SceneDetectionOptions> {

	public static final Logger log = LoggerFactory.getLogger(SceneDetectionNode.class);

	@PortDoc(label = "Video", description = "The video to cut at its shot boundaries")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	@PortDoc(label = "Scenes", description = "The detected scene boundaries as time-coded segments")
	public static final OutputPort<String> OUT_SCENES = OutputPort.one("scenes", ContentTypeRegistry.STRUCT_SEGMENTS, String.class);

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
				ctx.output(OUT_SCENES, cached);
				return ctx.origin(LOCAL).next();
			}
			// Closing the video releases the native capture and its decode buffers - it used to be left open for the lifetime of the JVM.
			try (VideoFile video = VideoFile.open(media.path())) {
				double fps = video.fps();
				SceneDetectionResult result = detector.detect(video);
				ctx.output(OUT_SCENES, result.toString());
				resultCache.put(path, result.toString());
				persist(ctx, asset, result, fps);
			}
			return ctx.origin(COMPUTED).next();
		} else {
			return ctx.skipped("no video media").next();
		}
	}

	/**
	 * Persist the detected scenes as the whole {@code SCENE} segment set of {@code asset_segment_comp} and record a ledger entry. The batch replace
	 * deletes any surplus scenes from a previous, longer run. Best-effort and a no-op when the asset is not yet known to Loom or we run offline.
	 *
	 * <p><b>Units:</b> {@code timeFrom} / {@code timeTo} carry <b>frame indices</b>, not the column's nominal milliseconds. Storing the raw detector
	 * output keeps the scene boundaries exact and independent of frame-rate rounding; the wall-clock time is derived downstream as
	 * {@code seconds = frame / fps}. So the conversion stays self-contained, the source {@code fps} travels with the set in {@code producerVersion}
	 * (e.g. {@code "fps=25.0"}).
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, SceneDetectionResult result, double fps) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			List<SegmentEntry> entries = new ArrayList<>();
			int seq = 0;
			for (Scene scene : result.scenes()) {
				// getFrom()/getTo() are frame indices - persisted as-is (see the units note above).
				entries.add(new SegmentEntry().setSeq(seq++).setTimeFrom(scene.getFrom()).setTimeTo(scene.getTo()));
			}
			SegmentCompCreateRequest request = new SegmentCompCreateRequest();
			request.setNodeKind(name());
			request.setSegmentType("SCENE");
			// Carry the frame rate so consumers can convert the frame-indexed bounds to time without re-opening the video.
			request.setProducerVersion(fps > 0 ? "fps=" + fps : null);
			request.setSegments(entries);
			client().createAssetSegmentComps(asset.getUuid(), request).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_segment_comp"));
		} catch (Exception e) {
			log.warn("Failed to persist scenes for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
