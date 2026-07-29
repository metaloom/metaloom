package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.node.payload.Detection;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.facedetect.video.VideoFace;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScannerReport;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.vertx.core.json.JsonObject;

public class FacedetectNode extends AbstractMediaNode<FacedetectNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FacedetectNode.class);

	/** The two alternatives of the descriptor's {@code media_alt} XOR group - one input, two shapes. */
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	public static final OutputPort<Long> OUT_FACE_COUNT = OutputPort.one("face_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/**
	 * The detected bounding boxes, <strong>one element per face</strong>.
	 *
	 * <p>
	 * This used to be a single JSON blob listing every face, which meant a downstream node could
	 * only ever process "all the faces of this asset" as one lump. Emitting a sequence is what
	 * lets the engine fan out: a consumer declaring a {@code ONE} detection input now runs once
	 * per face, and one declaring a {@code MANY} input gathers them back per asset.
	 * </p>
	 *
	 * <p>
	 * Each element is a JSON <em>string</em> rather than a structured value on purpose: node
	 * outputs are serialized as {@code key=value.toString()} by {@code XAttrNodeCache} and come
	 * back as plain strings on a cache hit, so anything structured has to be explicitly encoded
	 * and re-parsed.
	 * </p>
	 *
	 * <pre>
	 * { "index": 0, "type": "face", "label": "face", "frame": 0,
	 *   "bbox": {"x":100,"y":50,"w":80,"h":80}, "confidence": 1.0,
	 *   "coordinates": "ABSOLUTE_PIXELS",
	 *   "imageWidth": 1920, "imageHeight": 1080 }   // dimensions absent on the video path
	 * </pre>
	 *
	 * <p>
	 * The {@code coordinates} marker travels on every element because the {@code detection}
	 * table's own geometry convention is ambiguous: {@code V2.43} documents {@code bbox_x} as
	 * normalized 0-1, this node writes absolute pixels, and nothing validates either claim.
	 * Consumers reading boxes from here never have to guess; consumers reading them back over
	 * REST do.
	 * </p>
	 *
	 * <p>
	 * The dimensions are omitted on the video path because {@code VideoFile} exposes no frame
	 * size and reading a frame purely to measure it is not worth the cost. The boxes there are
	 * still native frame pixels - {@code VideoFaceScanner} either detects at full resolution or
	 * rescales its boxes back before returning them.
	 * </p>
	 */
	public static final OutputPort<String> OUT_DETECTIONS = OutputPort.many("detections", ContentTypeRegistry.DETECTION_FACE, String.class);

	/** In-heap skip cache of the face-detection outputs, keyed by media path, to avoid re-scanning within this worker's lifetime.
	 * Non-durable - the durable detections live in Loom. */
	private final LocalResultCache<CachedDetections> resultCache = new LocalResultCache<>(50_000);

	/**
	 * What a completed detection run produced, held for the worker's lifetime.
	 *
	 * <p>
	 * The elements are kept as a list rather than as one encoded blob so a cache hit re-emits the
	 * <em>same sequence</em> a fresh run would - a cache that collapsed them would silently change
	 * how many downstream per-element tasks the engine spawns.
	 * </p>
	 */
	private record CachedDetections(String flag, List<String> elements) {
	}

	private static final int WINDOW_COUNT = 50;

	private InspireFacedetector inspireface;
	private VideoFaceScanner videoScanner;

	@Inject
	public FacedetectNode(@Nullable LoomClient client, CortexOptions cortexOption, FacedetectNodeOptions options, InspireFacedetector inspireface, VideoFaceScanner videoScanner) {
		super(client, cortexOption, options);
		this.inspireface = inspireface;
		this.videoScanner = videoScanner;
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "facedetect";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit the locally cached face counts instead of re-scanning. On a hit the durable detections already exist in Loom, so we also skip
		// re-persisting.
		CachedDetections cached = resultCache.get(path);
		if (cached != null) {
			emit(ctx, cached.flag(), cached.elements());
			return ctx.origin(LOCAL).next();
		}

		NodeResult result;
		if (media.isVideo()) {
			result = processVideo(ctx, asset);
		} else if (media.isImage()) {
			result = processImage(ctx, asset);
		} else {
			return ctx.skipped("No visual media").next();
		}

		// Snapshot the outputs for the worker-lifetime skip cache once detection actually ran (the flag is set on both "faces found" and "none").
		if (ctx.outputs().containsKey(OUT_FLAG.id())) {
			// A MANY port that emitted nothing is absent from the accumulator entirely, so an image
			// with no faces has no "detections" entry at all - reading it unguarded threw.
			PortOutput detections = ctx.outputs().get(OUT_DETECTIONS.id());
			List<String> elements = detections == null
				? List.of()
				: detections.values().stream().map(String::valueOf).toList();
			resultCache.put(path, new CachedDetections(ctx.outputs().get(OUT_FLAG.id()).single().toString(), elements));
		}
		return result;
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage image = ImageIO.read(media.file());
		List<? extends Face> faces = inspireface.detectFaces(image);

		int count = faces != null ? faces.size() : 0;

		List<Detection> detections = new ArrayList<>();
		if (faces != null) {
			for (Face face : faces) {
				FaceBox box = face.box();
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					0, 1.0f, "face"));
			}
		}
		emit(ctx, count > 0 ? "SUCCESS" : "NONE",
			detectionElements(detections, image.getWidth(), image.getHeight()));
		persist(ctx, asset, detections);
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();

		try (VideoFile video = Videos.open(media.absolutePath())) {
			VideoFaceScannerReport report = videoScanner.scan(video, WINDOW_COUNT);
			List<VideoFace> faces = report.getFaces();

			List<Detection> detections = new ArrayList<>();
			for (VideoFace vf : faces) {
				FaceBox box = vf.box();
				int frameIndex = vf.getFrame() != null ? vf.getFrame().intValue() : 0;
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					frameIndex, 1.0f, "face"));
			}
			// Dimensions omitted: VideoFile exposes no frame size, and decoding a frame purely to measure it is not worth the cost.
			emit(ctx, "SUCCESS", detectionElements(detections, null, null));
			persist(ctx, asset, detections);
			return ctx.origin(COMPUTED).next();
		} catch (InterruptedException | IOException | URISyntaxException e) {
			log.error("Failed to process video", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Write the three output ports. The count is derived from the element sequence rather than
	 * tracked separately, so the two can never disagree.
	 */
	private void emit(NodeContext<LoomMedia> ctx, String flag, List<String> elements) {
		ctx.output(OUT_FACE_COUNT, (long) elements.size());
		ctx.output(OUT_FLAG, flag);
		for (String element : elements) {
			ctx.outputElement(OUT_DETECTIONS, element);
		}
	}

	/**
	 * Build the {@link #OUT_DETECTIONS} elements: one encoded box per face, each carrying the
	 * coordinate convention and the dimensions it was measured against.
	 *
	 * <p>
	 * The convention and dimensions are repeated on every element rather than hoisted into a
	 * wrapper, because an element is what travels downstream on its own - a per-face consumer
	 * receives exactly one of these and nothing else.
	 * </p>
	 *
	 * @param detections  the detected faces
	 * @param imageWidth  width of the image the boxes were measured against, or null when unknown (the video path)
	 * @param imageHeight height of that image, or null
	 * @return one encoded document per detection, in detection order
	 */
	private List<String> detectionElements(List<Detection> detections, Integer imageWidth, Integer imageHeight) {
		List<String> elements = new ArrayList<>(detections.size());
		int index = 0;
		for (Detection detection : detections) {
			BoundingBox box = detection.boundingBox();
			JsonObject element = new JsonObject()
				.put("index", index++)
				.put("type", "face")
				.put("label", detection.label())
				.put("frame", detection.frameIndex())
				.put("bbox", new JsonObject()
					.put("x", box.x())
					.put("y", box.y())
					.put("w", box.width())
					.put("h", box.height()))
				.put("confidence", detection.confidence())
				.put("coordinates", "ABSOLUTE_PIXELS");
			if (imageWidth != null && imageHeight != null) {
				element.put("imageWidth", imageWidth).put("imageHeight", imageHeight);
			}
			elements.add(element.encode());
		}
		return elements;
	}

	/**
	 * Persist detected faces as the asset's {@code facedetect} detection set and record a ledger entry. Each face becomes a detection row keyed by
	 * (asset, node_kind, frame_number, detection_index), so re-running the node upserts rather than appends. Best-effort and a no-op when the asset is
	 * not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, List<Detection> detections) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			List<DetectionCreateRequest> items = new ArrayList<>();
			int index = 0;
			for (Detection detection : detections) {
				BoundingBox box = detection.boundingBox();
				items.add(new DetectionCreateRequest()
					.setType("face")
					.setNodeKind(name())
					.setDetectionIndex(index++)
					.setFrameNumber(detection.frameIndex())
					.setBboxX((float) box.x())
					.setBboxY((float) box.y())
					.setBboxWidth((float) box.width())
					.setBboxHeight((float) box.height())
					.setConfidence(detection.confidence()));
			}
			client().bulkCreateAssetDetections(asset.getUuid(), new DetectionBulkCreateRequest().setDetections(items)).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("detection"));
		} catch (Exception e) {
			log.warn("Failed to persist detections for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
