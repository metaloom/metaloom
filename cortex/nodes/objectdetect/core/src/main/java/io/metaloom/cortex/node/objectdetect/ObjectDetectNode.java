package io.metaloom.cortex.node.objectdetect;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import io.metaloom.cortex.api.node.preview.ImagePreviews;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.node.spec.PortGroupDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.objectdetect.video.ObjectScanReport;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.vertx.core.json.JsonObject;

/**
 * Detects objects in images and video frames with YOLO.
 *
 * <p>
 * The first producer of {@code detection/object}. The content type, the {@code detection} table's
 * {@code objectdetection} type and its indexed {@code label} column all predate this node — they
 * were written in anticipation of it and nothing filled them until now.
 * </p>
 *
 * <p>
 * The element format on {@link #OUT_DETECTIONS} is deliberately the one {@code facedetect} emits,
 * because {@code scene-layout} and {@code dominant-color} already parse it. Matching it is what lets
 * this node feed both of them without a line of change on either side.
 * </p>
 */
@NodeSpec(nodeId = "objectdetect", name = "Object Detection", icon = "detection", category = NodeCategory.ANALYSIS,
	description = "Detect objects in images and video frames with YOLO.",
	// One: the native detector is a single unguarded global, so parallel calls into it are
	// unserialized access to one ONNX session. YoloObjectDetector locks as well - this is the
	// declaration, that is the guarantee.
	defaultConcurrency = 1,
	inputGroups = @PortGroupDoc(id = "media_alt", mode = PortGroupMode.XOR, label = "Media"))
public class ObjectDetectNode extends AbstractMediaNode<ObjectDetectNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ObjectDetectNode.class);

	/** The two alternatives of the {@code media_alt} XOR group — one input, two shapes. */
	@PortDoc(label = "Image", description = "A still image to search for objects", group = "media_alt")
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Video", description = "A video whose frames are sampled and searched", group = "media_alt")
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	/**
	 * The detected objects, <strong>one element per detection</strong>.
	 *
	 * <p>
	 * Emitting a sequence rather than one blob is what lets the engine fan out: a consumer declaring a
	 * {@code ONE} detection input runs once per object, one declaring {@code MANY} gathers them back
	 * per asset. {@code scene-layout} is the second kind, {@code image-manipulation}'s subject crop
	 * the first.
	 * </p>
	 *
	 * <pre>
	 * { "index": 0, "type": "object", "label": "person", "frame": 0,
	 *   "bbox": {"x":100,"y":50,"w":80,"h":80}, "confidence": 0.87,
	 *   "coordinates": "ABSOLUTE_PIXELS",
	 *   "imageWidth": 1920, "imageHeight": 1080, "classId": 14 }
	 * </pre>
	 *
	 * <p>
	 * Every field but {@code classId} is exactly what {@code facedetect} emits, and for the same
	 * reasons — in particular {@code coordinates}, which exists because the {@code detection} table
	 * documents its geometry as normalized 0-1 while both detector nodes write absolute pixels and
	 * nothing validates either claim. Consumers reading boxes from this port never have to guess.
	 * </p>
	 */
	@PortDoc(label = "Detections",
		description = "One element per detected object, so a downstream node can run once per object rather than once per file", order = 10)
	public static final OutputPort<String> OUT_DETECTIONS = OutputPort.many("detections", ContentTypeRegistry.DETECTION_OBJECT, String.class);

	/**
	 * The distinct class names found, one element each.
	 *
	 * <p>
	 * Wires straight into the {@code tag} node's {@code labels} port and its {@code LABELS} strategy,
	 * which turns every element into a tag — "what is in this asset" with no glue node in between.
	 * Distinct rather than one-per-detection because a clip with forty cars in it should produce the
	 * tag {@code car}, not forty of them.
	 * </p>
	 *
	 * <p>
	 * ⚠️ This node has <strong>two</strong> {@code MANY} outputs, and they are different lengths.
	 * Wiring both into a single downstream node zips sequences that do not correspond — take one or
	 * the other.
	 * </p>
	 */
	@PortDoc(label = "Labels", description = "The distinct classes found, one element each; feeds the tag node directly", order = 20)
	public static final OutputPort<String> OUT_LABELS = OutputPort.many("labels", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Object Count", description = "How many objects were detected", order = 30)
	public static final OutputPort<Long> OUT_OBJECT_COUNT = OutputPort.one("object_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item", order = 40)
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** No objects found. Not a failure: an empty room is a valid answer. */
	static final String FLAG_NONE = "NONE";

	/** Objects found and the scan ran to the end of the media. */
	static final String FLAG_SUCCESS = "SUCCESS";

	/**
	 * Objects found, but {@code maxDetections} stopped the scan early.
	 *
	 * <p>
	 * Its own flag rather than {@code SUCCESS}, because the difference between "this is what is in the
	 * file" and "this is the first N of what is in the file" is exactly what a consumer of a truncated
	 * detection set needs to know.
	 * </p>
	 */
	static final String FLAG_CAPPED = "CAPPED";

	/** Longest edge of a per-object crop preview. */
	private static final int OBJECT_PREVIEW_EDGE_PX = 192;

	/** Margin around a box when cropping, as a fraction of the box, so the crop shows context. */
	private static final double OBJECT_CROP_PADDING = 0.15d;

	/**
	 * In-heap skip cache of this node's outputs, for the worker's lifetime. Non-durable — the durable
	 * detections live in Loom.
	 */
	private final LocalResultCache<CachedDetections> resultCache = new LocalResultCache<>(50_000);

	/**
	 * What a completed scan produced.
	 *
	 * <p>
	 * The elements are kept as lists rather than as one encoded blob so a cache hit re-emits the
	 * <em>same sequences</em> a fresh run would — collapsing them would silently change how many
	 * downstream per-element tasks the engine spawns.
	 * </p>
	 */
	private record CachedDetections(String flag, List<String> elements, List<String> labels) {
	}

	private final ObjectDetector detector;
	private final VideoObjectScanner videoScanner;

	@Inject
	public ObjectDetectNode(@Nullable LoomClient client, CortexOptions cortexOption, ObjectDetectNodeOptions options,
		ObjectDetector detector, VideoObjectScanner videoScanner) {
		super(client, cortexOption, options);
		this.detector = detector;
		this.videoScanner = videoScanner;
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "objectdetect";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		String cacheKey = cacheKey(media);

		// Re-emit the cached outputs instead of re-scanning. On a hit the durable detections already
		// exist in Loom, so we skip re-persisting too. Debug runs go the long way round: the cache holds
		// the ports and nothing else, so a hit would re-emit boxes with no frame and no crops - the same
		// file showing its objects the first time it is examined and not the second is precisely what
		// makes a debugging view untrustworthy.
		CachedDetections cached = resultCache.get(cacheKey);
		if (cached != null && !ctx.capturePreviews()) {
			emit(ctx, cached.flag(), cached.elements(), cached.labels());
			return ctx.origin(LOCAL).next();
		}

		NodeResult result;
		try {
			if (media.isVideo()) {
				result = processVideo(ctx, asset);
			} else if (media.isImage()) {
				result = processImage(ctx, asset);
			} else {
				return ctx.skipped("No visual media").next();
			}
		} catch (Exception e) {
			log.error("Failed to detect objects in {}", media.absolutePath(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			// abort(), not next(): NodeContextImpl.next() looks only at the skip reason, so a failure
			// returned through it reports SUCCESS. facedetect's video path still does that and is wrong.
			return ctx.failure(e.getMessage()).abort();
		}

		// Snapshot the outputs for the skip cache once detection actually ran. The flag is set on every
		// path, including "found nothing".
		if (ctx.outputs().containsKey(OUT_FLAG.id())) {
			resultCache.put(cacheKey, new CachedDetections(
				ctx.outputs().get(OUT_FLAG.id()).single().toString(),
				elementsOf(ctx, OUT_DETECTIONS),
				elementsOf(ctx, OUT_LABELS)));
		}
		return result;
	}

	/**
	 * The skip-cache key: the media, plus everything about the configuration that changes the answer.
	 *
	 * <p>
	 * The path alone would be enough for a node that can only appear once, which is what
	 * {@code facedetect} assumes. This one carries a model path and a set of filters, so two instances
	 * in one graph — a permissive pass and a {@code person}-only pass — would otherwise serve each
	 * other's results.
	 * </p>
	 */
	private String cacheKey(LoomMedia media) {
		ObjectDetectNodeOptions o = options();
		return media.absolutePath() + "|" + o.getModelPath() + "|" + o.getMinConfidence() + "|" + o.getVideoChopRate()
			+ "|" + o.getVideoScaleSize() + "|" + o.getMaxDetections() + "|" + o.normalizedClassFilter();
	}

	/**
	 * The emitted elements of a {@code MANY} port, or an empty list.
	 *
	 * <p>
	 * A {@code MANY} port that emitted nothing is absent from the accumulator entirely, so an image
	 * with no objects has no {@code detections} entry at all — reading it unguarded throws.
	 * </p>
	 */
	private static List<String> elementsOf(NodeContext<LoomMedia> ctx, OutputPort<String> port) {
		PortOutput output = ctx.outputs().get(port.id());
		return output == null ? List.of() : output.values().stream().map(String::valueOf).toList();
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage image = ImageIO.read(media.file());
		if (image == null) {
			// ImageIO returns null rather than throwing for a format it has no reader for. Failing is
			// right: the pipeline classified this as an image and we were asked to detect in it.
			throw new IOException("Could not decode " + media.absolutePath() + " as an image");
		}

		List<DetectedObject> detections = detectImage(image);
		boolean capped = detections.size() > options().getMaxDetections();
		if (capped) {
			detections = detections.subList(0, options().getMaxDetections());
		}

		emit(ctx, flagFor(detections, capped),
			detectionElements(detections, image.getWidth(), image.getHeight()),
			distinctLabels(detections));
		previewDetections(ctx, new PreviewFrame(image, null), detections);
		persist(ctx, asset, detections);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Run detection over one decoded image at its native resolution.
	 *
	 * <p>
	 * Images are not downscaled the way video frames are: {@code videoScaleSize} pays for itself over
	 * thousands of frames, and a still costs one inference either way. So the scale factor is 1 and
	 * frame 0 is the only frame there is.
	 * </p>
	 */
	private List<DetectedObject> detectImage(BufferedImage image) {
		List<DetectedObject> kept = new ArrayList<>();
		for (ObjectDetection detection : detector.detect(image)) {
			DetectedObject accepted = DetectionFilter.accept(detection, 0, 1.0d, options());
			if (accepted != null) {
				kept.add(accepted);
			}
		}
		return kept;
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		try (VideoFile video = Videos.open(media.absolutePath())) {
			ObjectScanReport report = videoScanner.scan(video, options());
			List<DetectedObject> detections = report.detections();

			// The frame size comes off the video's own properties - no decode, no seek - so the
			// ABSOLUTE_PIXELS boxes always travel with something to measure them against.
			emit(ctx, flagFor(detections, report.capped()),
				detectionElements(detections, video.width(), video.height()),
				distinctLabels(detections));
			// Guarded, unlike the image path: reading the frame back means a seek and a decode, which is
			// real work rather than a value we already have in hand.
			if (ctx.capturePreviews()) {
				previewDetections(ctx, detectionFrame(video, detections), detections);
			}
			persist(ctx, asset, detections);
			log.debug("Detected {} objects in {} over {} frames", detections.size(), media.absolutePath(), report.framesScanned());
			return ctx.origin(COMPUTED).next();
		}
	}

	private static String flagFor(List<DetectedObject> detections, boolean capped) {
		if (detections.isEmpty()) {
			return FLAG_NONE;
		}
		return capped ? FLAG_CAPPED : FLAG_SUCCESS;
	}

	/**
	 * Write the four output ports. The count is derived from the element sequence rather than tracked
	 * separately, so the two can never disagree.
	 */
	private void emit(NodeContext<LoomMedia> ctx, String flag, List<String> elements, List<String> labels) {
		ctx.output(OUT_OBJECT_COUNT, (long) elements.size());
		ctx.output(OUT_FLAG, flag);
		for (String element : elements) {
			ctx.outputElement(OUT_DETECTIONS, element);
		}
		for (String label : labels) {
			ctx.outputElement(OUT_LABELS, label);
		}
		ctx.preview(OUT_DETECTIONS, detectionsMarkdown(elements));
	}

	/** The distinct class names, in first-seen order so the sequence is stable across runs. */
	private static List<String> distinctLabels(List<DetectedObject> detections) {
		Set<String> labels = new LinkedHashSet<>();
		for (DetectedObject detection : detections) {
			labels.add(detection.labelOrId());
		}
		return List.copyOf(labels);
	}

	/**
	 * Build the {@link #OUT_DETECTIONS} elements: one encoded box per object, each carrying the
	 * coordinate convention and the dimensions it was measured against.
	 *
	 * <p>
	 * Both are repeated on every element rather than hoisted into a wrapper, because an element is
	 * what travels downstream on its own — a per-object consumer receives exactly one of these.
	 * </p>
	 *
	 * @param detections  the detected objects, in order
	 * @param imageWidth  width of the frame the boxes were measured against
	 * @param imageHeight height of that frame
	 */
	private List<String> detectionElements(List<DetectedObject> detections, int imageWidth, int imageHeight) {
		List<String> elements = new ArrayList<>(detections.size());
		int index = 0;
		for (DetectedObject detection : detections) {
			BoundingBox box = detection.box();
			elements.add(new JsonObject()
				.put("index", index++)
				.put("type", "object")
				.put("label", detection.labelOrId())
				.put("frame", detection.frameIndex())
				.put("bbox", new JsonObject()
					.put("x", box.x())
					.put("y", box.y())
					.put("w", box.width())
					.put("h", box.height()))
				.put("confidence", detection.confidence())
				.put("coordinates", "ABSOLUTE_PIXELS")
				.put("imageWidth", imageWidth)
				.put("imageHeight", imageHeight)
				.put("classId", detection.classId())
				.encode());
		}
		return elements;
	}

	/**
	 * Describe the detections as a table, for the run debugging view.
	 *
	 * <p>
	 * The default rendering shows each element as the JSON document it is, which is accurate and
	 * unreadable. A column of labels next to a column of confidences answers "what did it find" at a
	 * glance; a column of encoded documents does not.
	 * </p>
	 */
	private static String detectionsMarkdown(List<String> elements) {
		if (elements.isEmpty()) {
			return "No objects detected.";
		}
		StringBuilder md = new StringBuilder("| # | label | confidence | box (x, y, w, h) | frame |\n|---|---|---|---|---|\n");
		for (String element : elements) {
			try {
				JsonObject object = new JsonObject(element);
				JsonObject box = object.getJsonObject("bbox", new JsonObject());
				md.append("| ").append(object.getValue("index", "")).append(" | ")
					.append(object.getValue("label", "")).append(" | ")
					.append(object.getValue("confidence", "")).append(" | ")
					.append(box.getValue("x", "")).append(", ").append(box.getValue("y", "")).append(", ")
					.append(box.getValue("w", "")).append(", ").append(box.getValue("h", "")).append(" | ")
					.append(object.getValue("frame", "")).append(" |\n");
			} catch (Exception e) {
				// A preview must never be able to fail the node that produced the real detections.
				return "Could not render " + elements.size() + " detections.";
			}
		}
		return md.toString();
	}

	/**
	 * Attach the picture behind the numbers: the frame the boxes were measured on, and one crop per
	 * object.
	 *
	 * <p>
	 * Nothing here runs outside a debug run — the crops are real image work, unlike the Markdown,
	 * which is cheap enough to build unconditionally and throw away.
	 * </p>
	 *
	 * @param source the image the boxes were measured against and which frame of the video it is
	 */
	private void previewDetections(NodeContext<LoomMedia> ctx, PreviewFrame source, List<DetectedObject> detections) {
		if (!ctx.capturePreviews() || detections.isEmpty()) {
			return;
		}
		BufferedImage frame = source.image();
		if (frame == null) {
			return;
		}
		// Stamped with the frame it came from, because the elements are spread over every frame the scan
		// sampled and only some were measured against this one. A viewer with no frame to compare
		// against has to draw all of them, piling a whole video's boxes onto one picture.
		ctx.preview(OUT_DETECTIONS, ImagePreviews.fromImage(frame).withFrame(source.frame()));
		for (int i = 0; i < detections.size(); i++) {
			BufferedImage crop = crop(frame, detections.get(i).box());
			if (crop != null) {
				// The crop *is* its detection, so it carries that detection's own frame rather than the
				// port-level one. A still is not frame 0 of anything, so the image path leaves it absent.
				ctx.preview(OUT_DETECTIONS, i, ImagePreviews.fromImage(crop, OBJECT_PREVIEW_EDGE_PX)
					.withFrame(source.frame() == null ? null : detections.get(i).frameIndex()));
			}
		}
	}

	/**
	 * A picture for the overlay together with the video frame it is, or {@code null} for the frame when
	 * the media was a still and no frame index applies.
	 */
	private record PreviewFrame(BufferedImage image, Integer frame) {
	}

	/**
	 * Cut an object out of the frame, with a little margin so it reads as a thing in a place.
	 *
	 * <p>
	 * Clamped on every side: detector boxes routinely run off the edge for an object at the border,
	 * and {@code getSubimage} throws rather than clipping.
	 * </p>
	 *
	 * @return the crop, or null when nothing of the box is inside the frame
	 */
	private static BufferedImage crop(BufferedImage frame, BoundingBox box) {
		int padX = (int) Math.round(box.width() * OBJECT_CROP_PADDING);
		int padY = (int) Math.round(box.height() * OBJECT_CROP_PADDING);
		int x = Math.max(0, box.x() - padX);
		int y = Math.max(0, box.y() - padY);
		int width = Math.min(frame.getWidth() - x, box.width() + 2 * padX);
		int height = Math.min(frame.getHeight() - y, box.height() + 2 * padY);
		if (width <= 0 || height <= 0 || x >= frame.getWidth() || y >= frame.getHeight()) {
			return null;
		}
		try {
			return frame.getSubimage(x, y, width, height);
		} catch (RuntimeException e) {
			// A preview must never be able to fail the node that produced the real detections.
			log.debug("Could not crop an object preview from {}x{}", frame.getWidth(), frame.getHeight(), e);
			return null;
		}
	}

	/**
	 * One frame for the boxes to be drawn on, seeked to wherever the first object was found.
	 *
	 * <p>
	 * The boxes carry a frame index each and a scan spans the whole video, so no single frame is right
	 * for all of them. The first detection's is the one that makes the overlay agree with at least one
	 * row of the table.
	 * </p>
	 *
	 * @return the frame and its index, both null if it could not be read — previews degrade, the node
	 *         does not
	 */
	private static PreviewFrame detectionFrame(VideoFile video, List<DetectedObject> detections) {
		if (detections.isEmpty()) {
			return new PreviewFrame(null, null);
		}
		int frameIndex = detections.get(0).frameIndex();
		try {
			video.seekToFrame(frameIndex);
			return new PreviewFrame(video.frameToImage(), frameIndex);
		} catch (RuntimeException e) {
			log.debug("Could not read a frame for the detection preview", e);
			return new PreviewFrame(null, null);
		}
	}

	/**
	 * Persist the objects as the asset's {@code objectdetect} detection set, and record a ledger row.
	 *
	 * <p>
	 * Each object becomes a {@code detection} row keyed by
	 * {@code (asset, node_kind, frame_number, detection_index)}, so a re-run upserts rather than
	 * appends. Best-effort and a no-op when the asset is not known to Loom or we run offline.
	 * </p>
	 *
	 * <p>
	 * ⚠️ Two known limits, both inherited from that key and shared with {@code facedetect}: it does not
	 * include {@code node_id}, so two instances of this kind in one graph overwrite each other; and an
	 * upsert never deletes, so a re-run that finds fewer objects leaves the previous run's
	 * higher-indexed rows in place.
	 * </p>
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, List<DetectedObject> detections) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			List<DetectionCreateRequest> items = new ArrayList<>(detections.size());
			int index = 0;
			for (DetectedObject detection : detections) {
				BoundingBox box = detection.box();
				items.add(new DetectionCreateRequest()
					.setType("objectdetection")
					.setNodeKind(name())
					.setProducerVersion(producerVersion())
					// The indexed column the whole schema comment is about: "Detected class for object
					// detection, e.g. dog". facedetect leaves it null because a face has no class.
					.setLabel(detection.labelOrId())
					.setDetectionIndex(index++)
					.setFrameNumber(detection.frameIndex())
					.setBboxX((float) box.x())
					.setBboxY((float) box.y())
					.setBboxWidth((float) box.width())
					.setBboxHeight((float) box.height())
					.setConfidence(detection.confidence()));
			}
			client().bulkCreateAssetDetections(asset.getUuid(), new DetectionBulkCreateRequest().setDetections(items)).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), null);
		} catch (Exception e) {
			log.warn("Failed to persist detections for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
		}
	}

	/**
	 * What produced these detections.
	 *
	 * <p>
	 * The model is part of it because it is the thing that changes the meaning of the output: the same
	 * asset scanned with a VOC model and a COCO model yields different labels for the same pixels, and
	 * a ledger row that did not say which would make the two indistinguishable after the fact.
	 * </p>
	 */
	String producerVersion() {
		String model = options().getModelPath();
		String name = model == null || model.isBlank() ? "unknown" : Paths.get(model).getFileName().toString();
		return "objectdetect/1:" + name;
	}
}
