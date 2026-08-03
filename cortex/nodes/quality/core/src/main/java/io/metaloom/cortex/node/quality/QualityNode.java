package io.metaloom.cortex.node.quality;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.artifact.MediaArtifacts;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;
import io.metaloom.opencv.core.CvType;
import io.metaloom.opencv.core.Mat;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.opencv.CVUtils;

@NodeSpec(nodeId = "quality", name = "Quality Analysis", icon = "high_quality", category = NodeCategory.ANALYSIS,
	description = "Analyze media quality: blurriness, resolution, bitrate.", defaultConcurrency = 4)
public class QualityNode extends AbstractMediaNode<QualityNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(QualityNode.class);

	@PortDoc(label = "Media", description = "The image or video to measure")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/**
	 * The whole metric set as one structured payload, so a consumer can take "the quality of this
	 * item" without wiring six edges. The individual ports below carry the same numbers for
	 * consumers that only want one.
	 */
	@PortDoc(label = "Quality Metrics",
		description = "The whole measurement bag - resolution, blurriness and bitrates - for a filter to threshold on")
	public static final OutputPort<String> OUT_METRICS = OutputPort.one("metrics", ContentTypeRegistry.STRUCT_QUALITY, String.class);

	@PortDoc(label = "Blurriness", description = "Variance of the Laplacian; the lower the value the blurrier the frame")
	public static final OutputPort<Double> OUT_BLURRINESS = OutputPort.one("blurriness", ContentTypeRegistry.SCALAR_NUMBER, Double.class);
	/**
	 * Resolution, for images and videos alike. The former split into {@code image_width} and
	 * {@code video_width} forced every consumer to try both keys and pick whichever was present -
	 * which is what {@code QualityFilterNode} and {@code AssetAttributeFilterNode} both did.
	 */
	@PortDoc(label = "Width", description = "Frame width in pixels")
	public static final OutputPort<Long> OUT_WIDTH = OutputPort.one("width", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Height", description = "Frame height in pixels")
	public static final OutputPort<Long> OUT_HEIGHT = OutputPort.one("height", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Frame Rate", description = "Frames per second; zero for a still image")
	public static final OutputPort<Double> OUT_FPS = OutputPort.one("fps", ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	@PortDoc(label = "Frame Count", description = "Total number of frames; one for a still image")
	public static final OutputPort<Long> OUT_FRAME_COUNT = OutputPort.one("frame_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** Upper bound for the in-heap skip cache. Blurriness/resolution analysis decodes frames, so we remember the metric set produced for each media
	 * during this worker's lifetime and re-emit it instead of recomputing. Non-durable - the durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 50_000;

	private final LocalResultCache<Map<String, Object>> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	@Inject
	public QualityNode(@Nullable LoomClient client, CortexOptions cortexOption, QualityNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "quality";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit the locally cached metric set instead of re-decoding frames. On a hit the durable copy already exists in Loom, so we also skip
		// re-persisting.
		Map<String, Object> cached = resultCache.get(path);
		if (cached != null) {
			emit(ctx, cached);
			return ctx.origin(LOCAL).next();
		}

		Map<String, Object> metrics = new LinkedHashMap<>();
		NodeResult result;
		if (media.isImage()) {
			result = processImage(ctx, asset, metrics);
		} else if (media.isVideo()) {
			result = processVideo(ctx, asset, metrics);
		} else {
			return ctx.skipped("No visual media").next();
		}

		// Snapshot the metrics for the worker-lifetime skip cache, but only for a successful run.
		if ("SUCCESS".equals(metrics.get(OUT_FLAG.id()))) {
			resultCache.put(path, new LinkedHashMap<>(metrics));
		}
		return result;
	}

	/**
	 * Write the metric set to its ports. Every individual port is derived from the same map that
	 * becomes {@link #OUT_METRICS}, so the aggregate and the scalars can never disagree.
	 */
	private void emit(NodeContext<LoomMedia> ctx, Map<String, Object> metrics) {
		writeIfPresent(ctx, metrics, OUT_BLURRINESS, Double.class);
		writeIfPresent(ctx, metrics, OUT_WIDTH, Long.class);
		writeIfPresent(ctx, metrics, OUT_HEIGHT, Long.class);
		writeIfPresent(ctx, metrics, OUT_FPS, Double.class);
		writeIfPresent(ctx, metrics, OUT_FRAME_COUNT, Long.class);
		writeIfPresent(ctx, metrics, OUT_FLAG, String.class);
		ctx.output(OUT_METRICS, new JsonObject(metrics).encode());
	}

	private <T> void writeIfPresent(NodeContext<LoomMedia> ctx, Map<String, Object> metrics, OutputPort<T> port, Class<T> type) {
		Object value = metrics.get(port.id());
		if (value != null) {
			ctx.output(port, type.cast(value));
		}
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx, AssetResponse asset, Map<String, Object> metrics) throws IOException {
		// Shared with every other image node in this segment - dominant-color reads the same
		// decode rather than opening the file again. Alone, this is still one ImageIO.read.
		BufferedImage image = MediaArtifacts.decodedImageOrNull(ctx);
		if (image == null) {
			return ctx.failure("Could not read image file").next();
		}

		QualityNodeOptions opts = options();

		// Resolution
		if (opts.isCheckResolution()) {
			metrics.put(OUT_WIDTH.id(), (long) image.getWidth());
			metrics.put(OUT_HEIGHT.id(), (long) image.getHeight());
		}

		// Blurriness via Laplacian operator
		if (opts.isCheckBlurriness()) {
			metrics.put(OUT_BLURRINESS.id(), computeBlurriness(image));
		}

		metrics.put(OUT_FLAG.id(), "SUCCESS");
		emit(ctx, metrics);
		persist(ctx, asset, metrics);
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx, AssetResponse asset, Map<String, Object> metrics) {
		LoomMedia media = ctx.media();
		QualityNodeOptions opts = options();

		try (VideoFile video = Videos.open(media.absolutePath())) {
			// Video resolution and metadata
			if (opts.isCheckResolution()) {
				metrics.put(OUT_WIDTH.id(), (long) video.width());
				metrics.put(OUT_HEIGHT.id(), (long) video.height());
				metrics.put(OUT_FPS.id(), video.fps());
				metrics.put(OUT_FRAME_COUNT.id(), video.length());
			}

			// Blurriness check on a sample frame from the middle of the video
			if (opts.isCheckBlurriness()) {
				video.seekToFrameRatio(0.5);
				Mat frame = video.frameToMat();
				if (frame != null) {
					metrics.put(OUT_BLURRINESS.id(), CVUtils.blurriness(frame));
					CVUtils.free(frame);
				}
			}

			metrics.put(OUT_FLAG.id(), "SUCCESS");
			emit(ctx, metrics);
			persist(ctx, asset, metrics);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to process video quality", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Persist the computed quality metrics as a {@code quality} JSON component and record a ledger entry. The typed image/video component tables do not
	 * carry blurriness/fps/frame-count, so the full metric set is stored as an opaque payload. Best-effort and a no-op when the asset is not yet known to
	 * Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, Map<String, Object> metrics) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonObject data = new JsonObject(metrics);
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("quality");
			request.setVariant("");
			request.setData(data);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist quality metrics for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

	/**
	 * Compute blurriness for a {@link BufferedImage} by converting it to a {@link Mat} first.
	 */
	private double computeBlurriness(BufferedImage image) {
		Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
		CVUtils.bufferedImageToMat(image, mat);
		double blurriness = CVUtils.blurriness(mat);
		CVUtils.free(mat);
		return blurriness;
	}
}
