package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import io.metaloom.cortex.api.node.preview.ImagePreviews;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.node.spec.PortGroupDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.facedetect.cluster.FaceCluster;
import io.metaloom.cortex.node.facedetect.cluster.FaceClusterResult;
import io.metaloom.cortex.node.facedetect.cluster.FaceClusterer;
import io.metaloom.cortex.node.facedetect.video.VideoFace;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScannerReport;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterMemberCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.vertx.core.json.JsonObject;

@NodeSpec(nodeId = "facedetect", name = "Face Detection", icon = "face", category = NodeCategory.ANALYSIS,
	description = "Detect and cluster faces in images and video frames.",
	defaultConcurrency = 2,
	inputGroups = @PortGroupDoc(id = "media_alt", mode = PortGroupMode.XOR, label = "Media"))
public class FacedetectNode extends AbstractMediaNode<FacedetectNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FacedetectNode.class);

	/** The two alternatives of the descriptor's {@code media_alt} XOR group - one input, two shapes. */
	@PortDoc(label = "Image", description = "A still image to search for faces", group = "media_alt")
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Video", description = "A video whose frames are sampled and searched", group = "media_alt")
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	// The descriptor lists the detections first, so the outputs carry explicit orders: the constants are
	// declared here in the order the class grew, which is not the order the editor draws them in.
	@PortDoc(label = "Face Count", description = "How many distinct faces survived clustering", order = 20)
	public static final OutputPort<Long> OUT_FACE_COUNT = OutputPort.one("face_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item", order = 30)
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
	 * Each element is a JSON <em>string</em> rather than a structured value because the port is
	 * declared as {@code String.class}: every element crosses the wire to Loom, so the encoding
	 * is explicit here and re-parsed by the consumer rather than left to whatever a serializer
	 * would have made of a nested object.
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
	@PortDoc(label = "Face Detections",
		description = "One element per detected face, so a downstream node can run once per face rather than once per file", order = 10)
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
	private record CachedDetections(String flag, List<String> elements, long clusterCount) {
	}

	private static final int WINDOW_COUNT = 50;

	/** The {@code cluster.type} this node proposes. Reviewed and confirmed into a {@code person}. */
	private static final String FACE_CLUSTER_TYPE = "face";

	/**
	 * Longest edge of a per-face crop preview.
	 *
	 * <p>
	 * Smaller than the 512 a whole frame gets, because a face is shown as a thumbnail rather than
	 * inspected, and because a crowd scene multiplies whatever this is by the number of faces found.
	 * </p>
	 */
	private static final int FACE_PREVIEW_EDGE_PX = 192;

	/**
	 * Margin around a face box when cropping, as a fraction of the box.
	 *
	 * <p>
	 * A detector box is tight to the facial features. Cut exactly on it and the crop reads as a mask -
	 * no hairline, no chin - which makes two different people hard to tell apart at thumbnail size.
	 * </p>
	 */
	private static final double FACE_CROP_PADDING = 0.35d;

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
		//
		// Debug runs go the long way round. The cache holds the ports and nothing else, so a hit would
		// re-emit the boxes with no frame and no face crops - the same file would show its faces the
		// first time it was examined and not the second, which is precisely the kind of thing that
		// makes a debugging view untrustworthy. Re-scanning is a cost a deliberately opt-in mode over
		// one halted item can afford.
		CachedDetections cached = resultCache.get(path);
		if (cached != null && !ctx.capturePreviews()) {
			emit(ctx, cached.flag(), cached.elements(), cached.clusterCount());
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
			PortOutput faceCount = ctx.outputs().get(OUT_FACE_COUNT.id());
			long clusterCount = faceCount == null ? 0L : ((Number) faceCount.single()).longValue();
			resultCache.put(path, new CachedDetections(ctx.outputs().get(OUT_FLAG.id()).single().toString(), elements, clusterCount));
		}
		return result;
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage image = ImageIO.read(media.file());
		// One pass produces the boxes and, when enabled, the vectors. Detecting and then embedding
		// separately would re-run detection unfiltered, and its ordinals would no longer line up.
		List<? extends Face> faces = inspireface.detectFaces(image, options().isEmbeddingsEnabled());

		int count = faces != null ? faces.size() : 0;

		List<Detection> detections = new ArrayList<>();
		if (faces != null) {
			for (Face face : faces) {
				FaceBox box = face.box();
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					0, confidenceOf(face), "face", face.getEmbedding()));
			}
		}
		FaceClusterResult clusters = cluster(detections);
		emit(ctx, count > 0 ? "SUCCESS" : "NONE",
			detectionElements(detections, clusters, image.getWidth(), image.getHeight()), subjectCount(clusters));
		// Crops are taken from the full-resolution decode, before anything downsamples it. A face is a
		// small part of a large frame - cutting it out of a 512px preview instead would yield a 30px
		// smudge on any modern source.
		previewDetections(ctx, new PreviewFrame(image, null), detections, null);
		// Cut once, used twice: the same crops become the debug preview and the durable images the review
		// UI shows. Taken from the full-resolution decode for the same reason the previews are.
		List<BufferedImage> crops = cropsFor(image, detections);
		persist(ctx, asset, detections, clusters, image.getWidth(), image.getHeight(), crops);
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();

		try (VideoFile video = Videos.open(media.absolutePath())) {
			VideoFaceScannerReport report = videoScanner.scan(video, WINDOW_COUNT, options().isEmbeddingsEnabled());
			List<VideoFace> faces = report.getFaces();

			List<Detection> detections = new ArrayList<>();
			for (VideoFace vf : faces) {
				FaceBox box = vf.box();
				int frameIndex = vf.getFrame() != null ? vf.getFrame().intValue() : 0;
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					frameIndex, confidenceOf(vf), "face", vf.getEmbedding()));
			}
			// The frame size comes off the video's own properties - no decode, no seek. It used to be
			// omitted here on the belief that VideoFile could not report it, which left every
			// video-path element without the dimensions its ABSOLUTE_PIXELS boxes are measured
			// against: anything wanting to draw those boxes, or convert them to the normalized
			// convention the detection table documents, had nothing to divide by.
			FaceClusterResult clusters = cluster(detections);
			emit(ctx, "SUCCESS", detectionElements(detections, clusters, video.width(), video.height()), subjectCount(clusters));
			List<BufferedImage> crops = faceCrops(faces);
			previewDetections(ctx, detectionFrame(video, detections), detections, crops);
			persist(ctx, asset, detections, clusters, video.width(), video.height(), crops);
			return ctx.origin(COMPUTED).next();
		} catch (InterruptedException | IOException | URISyntaxException e) {
			log.error("Failed to process video", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Write the three output ports.
	 *
	 * <p>
	 * {@link #OUT_FACE_COUNT} carries the number of <em>distinct subjects</em>, not the number of boxes - forty detections of two people in a video is
	 * two. That is what the port has always documented ("how many distinct faces survived clustering") and, until clustering existed, never what it
	 * emitted.
	 * </p>
	 */
	private void emit(NodeContext<LoomMedia> ctx, String flag, List<String> elements, long clusterCount) {
		ctx.output(OUT_FACE_COUNT, clusterCount);
		ctx.output(OUT_FLAG, flag);
		for (String element : elements) {
			ctx.outputElement(OUT_DETECTIONS, element);
		}
		ctx.preview(OUT_DETECTIONS, detectionsMarkdown(elements));
	}

	/**
	 * Group the detections that carry a vector into proposed subjects.
	 *
	 * <p>
	 * Scoped to this asset: it answers "who appears in this file", not "who is this person". Best-effort - a clustering failure must not lose the
	 * detections, which are useful on their own.
	 * </p>
	 */
	private FaceClusterResult cluster(List<Detection> detections) {
		int count = detections == null ? 0 : detections.size();
		if (!options().isEmbeddingsEnabled()) {
			// Every face is unattributed rather than absent - see subjectCount().
			return new FaceClusterResult(List.of(), 0, count);
		}
		try {
			FaceClusterResult result = FaceClusterer.cluster(detections, options().getFaceClusterEPS(), options().getFaceClusterMinimum());
			log.info("Clustered {} embedded face(s) into {} subject(s); {} face(s) had no vector",
				result.embeddedCount(), result.count(), result.skippedCount());
			return result;
		} catch (RuntimeException e) {
			log.warn("Could not cluster faces: {}", e.getMessage());
			return new FaceClusterResult(List.of(), 0, count);
		}
	}

	/**
	 * How many distinct people the node believes it saw.
	 *
	 * <p>
	 * Clusters, plus every face that could not be attributed to one. A face with no vector is still a face: counting only clusters would report zero
	 * for a run with embeddings switched off, or for one where the recognition pass failed - turning a degraded result into a silently empty one. An
	 * unattributed face is counted as its own subject, which can over-count when the same person appears twice unmatched, but never under-counts.
	 * </p>
	 */
	private static long subjectCount(FaceClusterResult clusters) {
		return (long) clusters.count() + clusters.skippedCount();
	}

	/**
	 * The detector's own confidence for a face, or {@code 1.0} when the backend did not report one.
	 *
	 * <p>
	 * This used to be a hard-coded {@code 1.0f}, so every stored face read as certain and the score could not be used to rank or filter anything. The
	 * video path always had the value; the image path only started reporting it once {@code detectFaces(BufferedImage, boolean)} was made symmetric with
	 * its {@code VideoFrame} counterpart, so the fallback stays for older video4j builds.
	 * </p>
	 */
	private static float confidenceOf(Face face) {
		Object value = face.get(InspireFacedetector.ATTR_CONFIDENCE);
		return value instanceof Number number ? number.floatValue() : 1.0f;
	}

	/**
	 * Attach the pictures behind the numbers: the frame the boxes were measured on, and one crop per
	 * face.
	 *
	 * <p>
	 * Face detection is the node whose output is least legible as data. {@code detections} is a list of
	 * encoded documents, and no amount of table formatting answers the two questions actually being
	 * asked - <em>where</em> in the picture is this box, and <em>who</em> is in it. The port-level
	 * preview is the frame, which the editor draws the boxes over; the per-element previews are the
	 * faces themselves.
	 * </p>
	 *
	 * <p>
	 * Nothing here runs outside a debug run: the crops are real image work, unlike the Markdown, which
	 * is cheap enough to build unconditionally and throw away.
	 * </p>
	 *
	 * @param ctx        the context to attach to
	 * @param source     the image the boxes were measured against and which frame of the video it is
	 * @param detections the faces, in element order
	 * @param crops      ready-made crops in the same order, or {@code null} to cut them from the image
	 */
	private void previewDetections(NodeContext<LoomMedia> ctx, PreviewFrame source, List<Detection> detections,
		List<BufferedImage> crops) {
		if (!ctx.capturePreviews() || detections.isEmpty()) {
			return;
		}
		BufferedImage frame = source.image();
		if (frame != null) {
			// Stamped with the frame it came from, because the elements are spread over every frame
			// the scan sampled and only some of them were measured against this one. A viewer with no
			// frame to compare against has to draw all of them, which turns four sampled frames of two
			// people into ten boxes piled on two faces.
			ctx.preview(OUT_DETECTIONS, ImagePreviews.fromImage(frame).withFrame(source.frame()));
		}
		for (int i = 0; i < detections.size(); i++) {
			BufferedImage crop = crops != null && i < crops.size() ? crops.get(i) : null;
			if (crop == null && frame != null) {
				crop = cropFace(frame, detections.get(i).boundingBox());
			}
			if (crop != null) {
				// The crop *is* its detection, so it carries that detection's own frame rather than the
				// port-level one — the ten faces in a video come from several different moments.
				ctx.preview(OUT_DETECTIONS, i,
					ImagePreviews.fromImage(crop, FACE_PREVIEW_EDGE_PX).withFrame(source.frame() == null ? null : detections.get(i).frameIndex()));
			}
		}
	}

	/**
	 * A picture for the overlay together with the video frame it is, or {@code null} for both when the
	 * media was a still and no frame index applies.
	 */
	private record PreviewFrame(BufferedImage image, Integer frame) {
	}

	/**
	 * Cut a face out of the frame, with enough margin around the box to show a head rather than a mask.
	 *
	 * <p>
	 * Clamped to the frame on every side: detector boxes routinely run off the edge for a face at the
	 * border, and {@code getSubimage} throws rather than clipping. Returns {@code null} for a box with
	 * nothing left inside the frame.
	 * </p>
	 */
	private static BufferedImage cropFace(BufferedImage frame, BoundingBox box) {
		int padX = (int) Math.round(box.width() * FACE_CROP_PADDING);
		int padY = (int) Math.round(box.height() * FACE_CROP_PADDING);
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
			log.debug("Could not crop a face preview from {}x{}", frame.getWidth(), frame.getHeight(), e);
			return null;
		}
	}

	/** One crop per detection, cut from the frame the boxes were measured on. Entries may be null where the box left nothing inside the frame. */
	private static List<BufferedImage> cropsFor(BufferedImage frame, List<Detection> detections) {
		List<BufferedImage> crops = new ArrayList<>(detections.size());
		for (Detection detection : detections) {
			crops.add(frame == null ? null : cropFace(frame, detection.boundingBox()));
		}
		return crops;
	}

	/**
	 * Store each face crop as an attachment keyed to the detection it depicts.
	 *
	 * <p>
	 * The node is the only place these images can come from. The server has no imaging libraries at all - it cannot decode a video frame - so a crop
	 * that is not written here can never be produced later, and the review UI would be left showing stand-in portraits fetched from a third party.
	 * Face crops are biometric data; they must not leave the deployment.
	 * </p>
	 *
	 * <p>
	 * Best-effort per crop: one image that fails to encode must not cost the others, and none of them are worth failing the detections over.
	 * </p>
	 */
	private void persistCrops(AssetResponse asset, DetectionBulkResponse stored, List<BufferedImage> crops) {
		if (crops == null || crops.isEmpty() || stored == null || stored.getDetections() == null) {
			return;
		}
		List<DetectionResponse> rows = stored.getDetections();
		if (rows.size() != crops.size()) {
			// The same one-to-one rule the embeddings follow: without it there is no safe way to say which
			// image belongs to which face, and a wrong crop is worse than no crop.
			log.warn("Skipping face crops for asset {}: {} crop(s) but {} stored detection(s)", asset.getUuid(), crops.size(), rows.size());
			return;
		}
		int written = 0;
		for (int i = 0; i < crops.size(); i++) {
			BufferedImage crop = crops.get(i);
			UUID detectionUuid = rows.get(i).getUuid();
			if (crop == null || detectionUuid == null) {
				continue;
			}
			File file = null;
			try {
				BufferedImage scaled = scaleToEdge(crop, FACE_PREVIEW_EDGE_PX);
				file = File.createTempFile("face-crop-", ".jpg");
				if (!ImageIO.write(scaled, "jpg", file)) {
					log.debug("No JPEG writer accepted the crop for detection {}", detectionUuid);
					continue;
				}
				client().uploadFaceCrop(file, asset.getUuid(), detectionUuid, String.valueOf(FACE_PREVIEW_EDGE_PX), name()).sync();
				written++;
			} catch (Exception e) {
				log.warn("Could not store the face crop for detection {}: {}", detectionUuid, e.getMessage());
			} finally {
				if (file != null && !file.delete()) {
					file.deleteOnExit();
				}
			}
		}
		if (written > 0) {
			log.info("Stored {} face crop(s) for asset {}", written, asset.getUuid());
		}
	}

	/**
	 * Scale an image so its longest edge is at most {@code edge}, preserving aspect ratio.
	 *
	 * <p>
	 * Only ever shrinks: enlarging a small crop would store more bytes than the detector ever saw.
	 * </p>
	 */
	private static BufferedImage scaleToEdge(BufferedImage image, int edge) {
		int longest = Math.max(image.getWidth(), image.getHeight());
		if (longest <= edge || longest == 0) {
			return image;
		}
		double factor = (double) edge / (double) longest;
		int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = scaled.createGraphics();
		try {
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return scaled;
	}

	/** The crops the video scanner already cut for its blur check, in detection order. */
	private static List<BufferedImage> faceCrops(List<VideoFace> faces) {
		List<BufferedImage> crops = new ArrayList<>(faces.size());
		for (VideoFace face : faces) {
			crops.add(face.getImage());
		}
		return crops;
	}

	/**
	 * One frame for the boxes to be drawn on, seeked to wherever the first face was found.
	 *
	 * <p>
	 * The boxes carry a frame index each and a scan may span the whole video, so there is no single
	 * frame that is right for all of them. The first detection's is the one that makes the overlay
	 * agree with at least one row of the table, which beats an arbitrary keyframe showing nothing.
	 * </p>
	 *
	 * @return the frame and its index; the image is {@code null} if it could not be read — previews
	 *         degrade, the node does not
	 */
	private static PreviewFrame detectionFrame(VideoFile video, List<Detection> detections) {
		if (detections.isEmpty()) {
			return new PreviewFrame(null, null);
		}
		int index = detections.get(0).frameIndex();
		try {
			video.seekToFrame(index);
			// The index travels back with the picture rather than being re-derived at the call site:
			// "which frame the overlay is of" and "which frame we seeked to" are the same decision, and
			// splitting them across two places is how they drift apart.
			return new PreviewFrame(video.frameToImage(), index);
		} catch (RuntimeException e) {
			log.debug("Could not read a frame for the detection preview", e);
			return new PreviewFrame(null, null);
		}
	}

	/**
	 * Describe the detections as a table, for the run debugging view.
	 *
	 * <p>
	 * The default rendering would show each element as the JSON document it is, which is accurate and
	 * unreadable: a row of {@code {"index":0,"type":"face","bbox":{...}}} tells you far less at a
	 * glance than a column of confidences next to a column of boxes. The node knows these elements are
	 * one-per-face and which of their fields matter; the content type does not.
	 * </p>
	 *
	 * @param elements the encoded detections, in detection order
	 */
	private static String detectionsMarkdown(List<String> elements) {
		if (elements.isEmpty()) {
			return "No faces detected.";
		}
		StringBuilder md = new StringBuilder("| # | confidence | box (x, y, w, h) | frame |\n|---|---|---|---|\n");
		for (String element : elements) {
			try {
				JsonObject face = new JsonObject(element);
				JsonObject box = face.getJsonObject("bbox", new JsonObject());
				md.append("| ").append(face.getValue("index", "")).append(" | ")
					.append(face.getValue("confidence", "")).append(" | ")
					.append(box.getValue("x", "")).append(", ").append(box.getValue("y", "")).append(", ")
					.append(box.getValue("w", "")).append(", ").append(box.getValue("h", "")).append(" | ")
					.append(face.getValue("frame", "")).append(" |\n");
			} catch (Exception e) {
				// A preview must never be able to fail the node that produced the real detections.
				return "Could not render " + elements.size() + " detections.";
			}
		}
		return md.toString();
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
	private List<String> detectionElements(List<Detection> detections, FaceClusterResult clusters, Integer imageWidth, Integer imageHeight) {
		int[] clusterOf = clusterIndexPerDetection(detections.size(), clusters);
		List<String> elements = new ArrayList<>(detections.size());
		int index = 0;
		for (Detection detection : detections) {
			BoundingBox box = detection.boundingBox();
			JsonObject element = new JsonObject()
				.put("index", index)
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
			// Which subject this face was attributed to, so a per-face consumer and the editor overlay can
			// colour by person without a REST round trip. -1 when clustering did not run or produced nothing.
			element.put("cluster", clusterOf[index]);
			if (imageWidth != null && imageHeight != null) {
				element.put("imageWidth", imageWidth).put("imageHeight", imageHeight);
			}
			elements.add(element.encode());
			index++;
		}
		return elements;
	}

	/** Cluster ordinal per detection index, or -1 where the detection was not attributed to any subject. */
	private static int[] clusterIndexPerDetection(int detectionCount, FaceClusterResult clusters) {
		int[] clusterOf = new int[detectionCount];
		java.util.Arrays.fill(clusterOf, -1);
		for (FaceCluster cluster : clusters.clusters()) {
			for (int member : cluster.members()) {
				if (member >= 0 && member < detectionCount) {
					clusterOf[member] = cluster.index();
				}
			}
		}
		return clusterOf;
	}

	/**
	 * Persist detected faces as the asset's {@code facedetect} detection set and record a ledger entry. Each face becomes a detection row keyed by
	 * (asset, node_kind, frame_number, detection_index), so re-running the node upserts rather than appends. Best-effort and a no-op when the asset is
	 * not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, List<Detection> detections, FaceClusterResult clusters,
		Integer frameWidth, Integer frameHeight, List<BufferedImage> crops) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			String producerVersion = producerVersion();
			List<DetectionCreateRequest> items = new ArrayList<>();
			int index = 0;
			for (Detection detection : detections) {
				BoundingBox box = detection.boundingBox();
				items.add(new DetectionCreateRequest()
					.setType("face")
					.setNodeKind(name())
					.setProducerVersion(producerVersion)
					.setLabel(detection.label())
					.setDetectionIndex(index++)
					.setFrameNumber(detection.frameIndex())
					// Normalised to a 0-1 factor of the frame, which is the convention the column has always
					// documented and the one every reader assumed. Writing absolute pixels put the boxes on a
					// scale nothing recorded, so a consumer had no way to know what to divide by - and a
					// derivative at another resolution could not use them at all.
					.setBboxX(normalise(box.x(), frameWidth))
					.setBboxY(normalise(box.y(), frameHeight))
					.setBboxWidth(normalise(box.width(), frameWidth))
					.setBboxHeight(normalise(box.height(), frameHeight))
					.setConfidence(detection.confidence()));
			}
			DetectionBulkResponse stored = client()
				.bulkCreateAssetDetections(asset.getUuid(), new DetectionBulkCreateRequest().setDetections(items))
				.sync()
				.body();
			persistCrops(asset, stored, crops);
			List<UUID> embeddingUuids = persistEmbeddings(asset, detections, stored, producerVersion);
			List<UUID> clusterUuids = persistClusters(asset, clusters, embeddingUuids, producerVersion);

			// The ledger points at the node's headline output. This used to be resultRef("detection") with no
			// uuids at all, and resultRef answers null for an empty varargs - so result_ref was always empty
			// even though the uuids were in hand two lines earlier.
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion,
				clusterUuids.isEmpty()
					? resultRef("detection", uuidsOf(stored))
					: resultRef("cluster", clusterUuids.toArray(UUID[]::new)));
		} catch (Exception e) {
			log.warn("Failed to persist detections for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

	/** The version stamped on everything this run writes: the node's own version qualified by the model that produced the vectors. */
	private String producerVersion() {
		String model = options().resolvedEmbeddingModel();
		return model == null || model.isBlank() ? "" : model;
	}

	/**
	 * Express a pixel coordinate as a 0-1 factor of the frame.
	 *
	 * <p>
	 * Falls back to the raw pixel value when the frame size is unknown or nonsensical: a box on the wrong scale is recoverable and visibly wrong, while
	 * a divide by zero would be neither.
	 * </p>
	 */
	private static float normalise(int value, Integer extent) {
		if (extent == null || extent <= 0) {
			log.warn("No frame extent to normalise a bounding box against; storing the raw pixel value {}", value);
			return value;
		}
		return (float) value / (float) extent;
	}

	private static UUID[] uuidsOf(DetectionBulkResponse stored) {
		if (stored == null || stored.getDetections() == null) {
			return new UUID[0];
		}
		return stored.getDetections().stream().map(DetectionResponse::getUuid).filter(java.util.Objects::nonNull).toArray(UUID[]::new);
	}

	/**
	 * Write the recognition vectors alongside the detections that were just stored.
	 *
	 * <p>
	 * Second of the two writes on purpose: {@code embedding.detection_uuid} points at a detection row, and those uuids only exist once the detection
	 * bulk call has returned. The response lists the stored detections in request order, which is what pairs each vector with the box it came from.
	 * </p>
	 *
	 * <p>
	 * Best-effort. A failure here is logged and leaves the detections in place - a face that is found but not yet matchable is a much better outcome
	 * than losing the detection as well, and re-running the node upserts both sets rather than duplicating them.
	 * </p>
	 */
	private List<UUID> persistEmbeddings(AssetResponse asset, List<Detection> detections, DetectionBulkResponse stored, String producerVersion) {
		List<UUID> embeddingUuids = new ArrayList<>(java.util.Collections.nCopies(detections.size(), (UUID) null));
		if (!options().isEmbeddingsEnabled() || stored == null) {
			return embeddingUuids;
		}
		List<DetectionResponse> rows = stored.getDetections();
		if (rows == null || rows.size() != detections.size()) {
			// Without a one-to-one correspondence there is no safe way to tell which vector belongs to which
			// row, and guessing would attach vectors to the wrong faces - silently, and permanently.
			if (rows != null && !rows.isEmpty()) {
				log.warn("Skipping embeddings for asset {}: {} detection(s) sent but {} stored", asset.getUuid(), detections.size(), rows.size());
			}
			return embeddingUuids;
		}
		try {
			List<EmbeddingCreateRequest> items = new ArrayList<>();
			// Detection index each request came from, so the returned uuids can be put back on the right faces.
			List<Integer> sourceIndex = new ArrayList<>();
			for (int i = 0; i < detections.size(); i++) {
				Detection detection = detections.get(i);
				if (!detection.hasEmbedding()) {
					continue;
				}
				float[] vector = detection.embedding();
				Float[] boxed = new Float[vector.length];
				for (int v = 0; v < vector.length; v++) {
					boxed[v] = vector[v];
				}
				items.add(new EmbeddingCreateRequest()
					.setType(FacedetectNodeOptions.EMBEDDING_TYPE)
					.setNodeKind(name())
					// Qualified by the pack, because the binding exposes no pack version at runtime and two packs'
					// vectors are not comparable. model is part of the row's identity and of the vector-index key,
					// so this is what keeps them in separate spaces.
					.setModel(options().resolvedEmbeddingModel())
					.setProducerVersion(producerVersion)
					.setVector(boxed)
					.setDimensions(vector.length)
					.setDetectionUuid(rows.get(i).getUuid())
					.setFrameNumber(detection.frameIndex())
					.setSubjectIndex(i)
					// The clusterer L2-normalises before comparing, and writes the vectors it normalised.
					.setNormalized(true)
					.setConfidence(detection.confidence()));
				sourceIndex.add(i);
			}
			if (items.isEmpty()) {
				return embeddingUuids;
			}
			EmbeddingBulkResponse response = client()
				.bulkCreateAssetEmbeddings(asset.getUuid(), new EmbeddingBulkCreateRequest().setEmbeddings(items))
				.sync()
				.body();
			List<EmbeddingResponse> embeddingRows = response == null ? null : response.getEmbeddings();
			if (embeddingRows != null && embeddingRows.size() == items.size()) {
				for (int i = 0; i < embeddingRows.size(); i++) {
					embeddingUuids.set(sourceIndex.get(i), embeddingRows.get(i).getUuid());
				}
			} else {
				// Without the uuids there is nothing to build cluster membership from. The embeddings are stored
				// either way; only the grouping is lost, and a re-run rebuilds it.
				log.warn("Stored embeddings for asset {} but the response carried no usable uuids; clusters will have no members", asset.getUuid());
			}
			log.info("Stored {} face embedding(s) for asset {}", items.size(), asset.getUuid());
		} catch (Exception e) {
			log.warn("Failed to persist embeddings for asset {}: {}", asset.getUuid(), e.getMessage());
		}
		return embeddingUuids;
	}

	/**
	 * Write the subjects found in this asset, each holding the embeddings attributed to it.
	 *
	 * <p>
	 * Third of the three writes, and for the same reason the embeddings are second: membership references embedding rows, and those uuids only exist
	 * once the embedding bulk call has returned.
	 * </p>
	 *
	 * <p>
	 * Best-effort, like the others. A cluster that fails to store leaves the detections and embeddings in place - faces that are found and matchable but
	 * not yet grouped is a far better outcome than losing them, and a re-run regroups them.
	 * </p>
	 *
	 * @return the uuids of the stored clusters, for the ledger
	 */
	private List<UUID> persistClusters(AssetResponse asset, FaceClusterResult clusters, List<UUID> embeddingUuids, String producerVersion) {
		if (clusters == null || clusters.count() == 0) {
			return List.of();
		}
		try {
			ClusterBulkCreateRequest request = new ClusterBulkCreateRequest();
			for (FaceCluster cluster : clusters.clusters()) {
				ClusterCreateItem item = new ClusterCreateItem()
					.setType(FACE_CLUSTER_TYPE)
					.setNodeKind(name())
					.setProducerVersion(producerVersion)
					.setClusterIndex(cluster.index())
					.setScore(cluster.score())
					.setCentroid(boxed(cluster.centroid()))
					.setModel(options().resolvedEmbeddingModel())
					.setDimensions(cluster.centroid() == null ? null : cluster.centroid().length);
				if (cluster.noise()) {
					// A face that matched nobody. Recorded as its own subject rather than discarded - a portrait is
					// exactly one face and would otherwise report nobody at all - but flagged, because "seen once"
					// and "seen and corroborated" are different things to a reviewer.
					item.setMeta(new JsonObject().put("noise", true));
				}
				List<Integer> members = cluster.members();
				for (int m = 0; m < members.size(); m++) {
					UUID embeddingUuid = embeddingUuids.get(members.get(m));
					if (embeddingUuid != null) {
						item.add(new ClusterMemberCreateItem()
							.setEmbeddingUuid(embeddingUuid.toString())
							.setConfidence(cluster.confidences()[m])
							.setOrigin("AUTO"));
					}
				}
				request.add(item);
			}
			ClusterBulkResponse stored = client()
				.bulkCreateAssetClusters(asset.getUuid(), request)
				.sync()
				.body();
			if (stored == null || stored.getClusters() == null) {
				return List.of();
			}
			log.info("Stored {} face cluster(s) for asset {} ({} stale proposal(s) retired)",
				stored.getClusters().size(), asset.getUuid(), stored.getPruned());
			return stored.getClusters().stream().map(ClusterResponse::getUuid).filter(java.util.Objects::nonNull).toList();
		} catch (Exception e) {
			log.warn("Failed to persist clusters for asset {}: {}", asset.getUuid(), e.getMessage());
			return List.of();
		}
	}

	private static Float[] boxed(float[] vector) {
		if (vector == null) {
			return null;
		}
		Float[] out = new Float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = vector[i];
		}
		return out;
	}
}
