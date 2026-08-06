package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.preview.ImagePreviews;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.node.spec.PortGroupDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.cortex.node.sam2.video.SampledFrames;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Segment Anything 2. Turns "where is it" into "which pixels are it".
 *
 * <p>
 * Every geometry Loom stores today is an axis-aligned rectangle. This node is the first producer of
 * per-pixel shape, in three flavours selected by {@link Sam2Mode}: segment everything, cut out the
 * boxes an upstream detector found, or follow a mask through a clip.
 * </p>
 *
 * <p>
 * The inference runs in the FastAPI sidecar ({@code sidecars/sam2}); this node is a pure HTTP client
 * via {@link Sam2Client}.
 * </p>
 *
 * <h2>Three coordinate spaces, all named</h2>
 *
 * <p>
 * Upstream boxes are measured against the <em>source</em> image or the video's native frame. The
 * sidecar is handed that frame downscaled to {@code maxDim} and answers in <em>that</em> space, which
 * is also the size of every produced mask. So the emitted metadata reports both pairs —
 * {@code width}/{@code height} for the masks, {@code imageWidth}/{@code imageHeight} for the source.
 * Without both, projecting a mask back onto the original is a guess, exactly as
 * {@code DepthmapNode.buildMeta} documents.
 * </p>
 *
 * <h2>Persistence</h2>
 *
 * <p>
 * Ledger only, following {@code ThumbnailNode}/{@code DepthmapNode}: the masks are written under
 * {@code metaPath/sam2_bin} and Loom records only that this node produced them, stamped with the
 * checkpoint. Loom has no byte-ingest endpoint for produced media, and {@code detection} has no
 * column for polygonal geometry — inventing one in {@code meta} would be a write path with no read
 * path, which is the defect {@code NEW_NODE.md} §1.4 exists to prevent.
 * </p>
 *
 * <p>
 * 🔴 Because the masks are worker-local — and there are N of them, not one — any node consuming them
 * must run on the same worker. Pin them into one affinity group. Wiring {@link #OUT_MASKS} into
 * {@code s3-sink} is the only supported way to get the bytes off the worker.
 * </p>
 *
 * <p>
 * 🔴 A note for the day someone writes a frame-extractor node: this node emits a {@code MANY} port,
 * so the graph analyzer rejects it outright if its media input is ever fed per-element ("nested
 * fan-out is not supported"). Nothing can do that today — no node emits {@code media/image} as a
 * sequence, and {@code artifact/image} cannot reach a {@code media/image} port because content-type
 * families never cross — but the error would read like a bug in this node rather than in the graph.
 * </p>
 */
@NodeSpec(nodeId = "sam2", name = "Segment Anything 2", icon = "layers", category = NodeCategory.ANALYSIS,
	description = "Segment objects in images and video with SAM 2 - everything automatically, the boxes an upstream "
		+ "detector found, or a mask tracked through a clip. Masks are written to a local cache as binary PNGs.",
	// The sidecar holds one model on one device and the video predictor holds a per-request memory
	// bank; parallel calls from a single worker only queue behind each other and inflate VRAM.
	defaultConcurrency = 1,
	inputGroups = @PortGroupDoc(id = "media_alt", mode = PortGroupMode.XOR, label = "Media"),
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor
	// advertises it. Segment-everything runs for minutes, so this node does.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "HTTP request timeout", min = "1", order = 9000))
public class Sam2Node extends AbstractMediaNode<Sam2NodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(Sam2Node.class);

	/** The two alternatives of the {@code media_alt} XOR group — one input, two shapes. */
	@PortDoc(label = "Image", description = "A still image to segment", group = "media_alt", order = 10)
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Video", description = "A video whose sampled frames are segmented or tracked", group = "media_alt", order = 20)
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	/**
	 * {@code MANY} so the whole detector branch is gathered into one execution — every box goes
	 * through a single call, which is one image encode rather than one per object.
	 */
	@PortDoc(label = "Detections", required = false, order = 30,
		description = "Boxes to segment, from any detector. Leave unwired to segment everything")
	public static final InputPort<String> IN_DETECTIONS = InputPort.many("detections", ContentTypeRegistry.DETECTION_ANY, String.class);

	@PortDoc(label = "Masks", order = 10,
		description = "One element per mask: the worker-local binary PNG a downstream node can open")
	public static final OutputPort<String> OUT_MASKS = OutputPort.many("masks", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Segmentation", order = 20,
		description = "Model, both dimension pairs, and one record per mask: area, score, box, label, frame and path")
	public static final OutputPort<String> OUT_SEGMENTS = OutputPort.one("segments", ContentTypeRegistry.STRUCT_MASKS, String.class);

	@PortDoc(label = "Overlay", order = 30,
		description = "The source frame with every mask tinted over it - the picture of what was segmented")
	public static final OutputPort<String> OUT_OVERLAY = OutputPort.one("overlay", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Mask Count", description = "How many masks were produced", order = 40)
	public static final OutputPort<Long> OUT_MASK_COUNT = OutputPort.one("mask_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item", order = 50)
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** Nothing segmented. Not a failure: a blank wall is a valid answer. */
	static final String FLAG_NONE = "NONE";

	/** Masks produced and nothing was cut short. */
	static final String FLAG_SUCCESS = "SUCCESS";

	/**
	 * Masks produced, but a cap stopped the work early.
	 *
	 * <p>
	 * Its own flag rather than {@code SUCCESS}, because the difference between "this is what is in the
	 * file" and "this is the first N of what is in the file" is exactly what a consumer of a truncated
	 * result needs to know — {@code objectdetect}'s precedent.
	 * </p>
	 */
	static final String FLAG_CAPPED = "CAPPED";

	static final String FLAG_FAILED = "FAILED";

	/** Written last; its presence is what commits an artifact directory. */
	static final String MANIFEST_NAME = "manifest.json";

	/** Longest edge of a per-mask preview. */
	private static final int MASK_PREVIEW_EDGE_PX = 192;

	private static final int RESULT_CACHE_SIZE = 10_000;

	/**
	 * In-heap skip cache of the manifest JSON, keyed by media path plus the option digest, for the
	 * worker's lifetime. The masks themselves are durable local artifacts under
	 * {@code metaPath/sam2_bin}.
	 */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final Sam2Client sam2Client;
	private final Sam2FrameSampler frameSampler;
	private final CortexOptions cortexOptions;

	@Inject
	public Sam2Node(@Nullable LoomClient client, CortexOptions cortexOptions, Sam2NodeOptions options,
		Sam2Client sam2Client, Sam2FrameSampler frameSampler) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.sam2Client = sam2Client;
		this.frameSampler = frameSampler;
	}

	@Override
	public void initialize() {
		// Eager rather than lazy inside the video branch: a lazy init would need its own
		// synchronization to save one native load. objectdetect settled this the same way.
		Video4j.init();
	}

	@Override
	public String name() {
		return "sam2";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		LoomMedia media = ctx.media();
		return media.isImage() || media.isVideo();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		List<UpstreamBox> boxes = readDetections(ctx);
		String digest = digest(boxes);
		String cacheKey = media.absolutePath() + "|" + digest;

		// Re-emit the cached outputs instead of re-inferring. On a hit the ledger entry already exists in
		// Loom, so re-persisting is skipped too. Debug runs go the long way round: the cache holds the
		// ports and nothing else, so a hit would re-emit paths with no overlay and no crops - the same
		// file showing its masks the first time it is examined and not the second is precisely what makes
		// a debugging view untrustworthy.
		String cached = resultCache.get(cacheKey);
		if (cached != null && !ctx.capturePreviews()) {
			JsonObject manifest = new JsonObject(cached);
			// The manifest is written after every mask, so its presence commits the whole set. One stat
			// call regardless of mask count, and a directory left half-written by a killed worker has
			// none - so the node recomputes rather than handing out a manifest naming files that are gone.
			if (Files.exists(Path.of(manifest.getString("dir")).resolve(MANIFEST_NAME))) {
				metrics.recordAiCacheHit("sam2");
				emit(ctx, manifest);
				return ctx.origin(LOCAL).next();
			}
		}

		String model = null;
		try {
			Sam2Mode mode = options().getMode();
			JsonObject manifest;
			if (media.isVideo()) {
				manifest = processVideo(ctx, mode, boxes, artifactDir(media, digest));
			} else if (mode == Sam2Mode.TRACK) {
				// Fail rather than skip: the worker was given a job it cannot do, and a skip reads as
				// "this item did not need processing".
				throw new IOException("TRACK needs a video, got the image " + media.absolutePath());
			} else {
				manifest = processImage(ctx, mode, boxes, artifactDir(media, digest));
			}
			if (manifest == null) {
				return ctx.skipped("PROMPTED needs boxes on the '" + IN_DETECTIONS.id() + "' port, none were wired").next();
			}
			model = manifest.getString("model");

			emit(ctx, manifest);
			resultCache.put(cacheKey, manifest.encode());
			ctx.print("DONE", manifest.getJsonArray("masks").size() + " masks "
				+ manifest.getInteger("width") + "x" + manifest.getInteger("height"));

			// The mask bytes live in the local sam2_bin cache; record the ledger marker that this node
			// produced them for the asset, stamped with the checkpoint. There is no byte-ingest endpoint
			// for produced media (same as ThumbnailNode/DepthmapNode/ImageGenNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(model), null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to segment {}", media.absolutePath(), e);
			ctx.output(OUT_FLAG, FLAG_FAILED);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(model), null);
			// abort(), not next(): NodeContextImpl.next() looks only at the skip reason, so a failure
			// returned through it reports SUCCESS. depthmap still does that and is wrong.
			return ctx.failure(e.getMessage()).abort();
		}
	}

	// ────────────────────────────────────────────────────────────────────────
	// Still images
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * Segment one still, in AUTOMATIC or PROMPTED.
	 *
	 * @return the manifest, or null when PROMPTED was asked to work with no boxes
	 */
	private JsonObject processImage(NodeContext<LoomMedia> ctx, Sam2Mode mode, List<UpstreamBox> boxes, Path dir) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage source = Sam2Images.read(media.file());
		BufferedImage posted = Sam2Images.downscale(source, options().getMaxDim());

		// A photo where the detector found nothing is a normal outcome, not a failure.
		if (mode == Sam2Mode.PROMPTED && boxes.isEmpty()) {
			return null;
		}
		List<Sam2Box> prompts = mode == Sam2Mode.PROMPTED
			? toPrompts(boxes, (double) posted.getWidth() / source.getWidth(), source.getWidth(), source.getHeight())
			: List.of();

		JsonObject response = call(() -> sam2Client.segment(Sam2Images.toBase64Png(posted), mode, prompts, options()));
		return writeImageResult(ctx, response, mode, posted, source.getWidth(), source.getHeight(), dir);
	}

	/**
	 * Decode every returned mask, write it, and assemble the manifest.
	 */
	private JsonObject writeImageResult(NodeContext<LoomMedia> ctx, JsonObject response, Sam2Mode mode, BufferedImage posted,
		int sourceWidth, int sourceHeight, Path dir) throws IOException {
		JsonArray masks = response.getJsonArray("masks", new JsonArray());
		List<BufferedImage> decoded = new ArrayList<>();
		JsonArray records = new JsonArray();

		for (int i = 0; i < masks.size(); i++) {
			JsonObject mask = masks.getJsonObject(i);
			BufferedImage image = Sam2Images.decodeBase64(mask.getString("png_b64"));
			Path target = dir.resolve(String.format("mask-%04d.png", i));
			Sam2Images.writePng(image, target);
			decoded.add(image);

			JsonObject record = new JsonObject()
				.put("index", i)
				.put("path", target.toString())
				.put("area", mask.getInteger("area", 0))
				.put("bbox", mask.getJsonObject("bbox"));
			copyIfPresent(mask, record, "score");
			copyIfPresent(mask, record, "label");
			copyIfPresent(mask, record, "promptIndex");
			records.add(record);
		}

		Path overlay = writeOverlay(posted, decoded, dir);
		previewMasks(ctx, decoded);

		// The mask space is whatever the sidecar says it is, not what this node posted. The two are
		// normally the same, but the sidecar clamps max_dim to its own SAM2_MAX_DIM, so a node asking
		// for more gets masks smaller than the image it sent. Reporting the posted size there would
		// hand every consumer a scale factor that is quietly wrong - so fall back to it only when the
		// response says nothing.
		return manifest(response, mode, records, dir, overlay,
			response.getInteger("width", posted.getWidth()),
			response.getInteger("height", posted.getHeight()),
			sourceWidth, sourceHeight,
			response.getJsonObject("truncated", new JsonObject()).getInteger("masks", 0), 0);
	}

	// ────────────────────────────────────────────────────────────────────────
	// Video
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * Sample the video, then either track through the sequence or segment one frame of it.
	 *
	 * @return the manifest, or null when PROMPTED was asked to work with no boxes
	 */
	private JsonObject processVideo(NodeContext<LoomMedia> ctx, Sam2Mode mode, List<UpstreamBox> boxes, Path dir) throws IOException {
		LoomMedia media = ctx.media();
		SampledFrames frames = frameSampler.sample(media.absolutePath(), options());
		if (frames.isEmpty()) {
			throw new IOException("No frames could be sampled from " + media.absolutePath());
		}
		if (mode == Sam2Mode.PROMPTED && boxes.isEmpty()) {
			return null;
		}
		if (mode == Sam2Mode.TRACK) {
			return trackVideo(ctx, frames, boxes, dir);
		}
		return segmentOneFrame(ctx, mode, frames, boxes, dir);
	}

	/**
	 * AUTOMATIC or PROMPTED on a video: run one segmentation over the configured annotation frame.
	 *
	 * <p>
	 * One frame rather than all of them, deliberately. Segment-everything over 64 frames is 64k
	 * forward passes and tens of thousands of mask files for one asset; the node would look like it
	 * hung. TRACK is the mode that spans the clip, and it exists precisely so this one does not have
	 * to.
	 * </p>
	 */
	private JsonObject segmentOneFrame(NodeContext<LoomMedia> ctx, Sam2Mode mode, SampledFrames frames, List<UpstreamBox> boxes, Path dir)
		throws IOException {
		int index = Math.min(options().getTrackFrame(), frames.size() - 1);
		BufferedImage frame = Sam2Images.decodeBase64(frames.jpegB64().get(index));

		List<Sam2Box> prompts = mode == Sam2Mode.PROMPTED
			? toPrompts(boxes, frames.scaleToSampled(), frames.nativeWidth(), frames.nativeHeight())
			: List.of();

		JsonObject response = call(() -> sam2Client.segment(frames.jpegB64().get(index), mode, prompts, options()));
		JsonObject manifest = writeImageResult(ctx, response, mode, frame, frames.nativeWidth(), frames.nativeHeight(), dir);
		manifest.put("frame", frames.frameNumbers().get(index));
		if (frames.capped()) {
			// The walk stopped early, so trackFrame may not be the frame the author had in mind.
			manifest.getJsonObject("truncated").put("frames", 1);
		}
		return manifest;
	}

	/**
	 * TRACK: prompt on one sampled frame and propagate through the rest.
	 */
	private JsonObject trackVideo(NodeContext<LoomMedia> ctx, SampledFrames frames, List<UpstreamBox> boxes, Path dir) throws IOException {
		List<Sam2TrackPrompt> prompts = trackPrompts(frames, boxes);
		if (prompts.isEmpty()) {
			throw new IOException("TRACK had no usable prompt box; wire a detector into '" + IN_DETECTIONS.id()
				+ "' or check that its boxes have area");
		}

		JsonObject response = call(() -> sam2Client.track(frames.jpegB64(), frames.frameNumbers(), prompts, options()));

		JsonArray records = new JsonArray();
		JsonArray responseFrames = response.getJsonArray("frames", new JsonArray());
		for (int f = 0; f < responseFrames.size(); f++) {
			JsonObject frame = responseFrames.getJsonObject(f);
			int frameNumber = frame.getInteger("frameNumber", f);
			JsonArray masks = frame.getJsonArray("masks", new JsonArray());
			for (int m = 0; m < masks.size(); m++) {
				JsonObject mask = masks.getJsonObject(m);
				int objId = mask.getInteger("objId", m);
				BufferedImage image = Sam2Images.decodeBase64(mask.getString("png_b64"));
				// Frame number and object id in the file name, so a directory listing is already an
				// index: one object's track is a glob, one moment across objects is a prefix.
				Path target = dir.resolve(String.format("f%07d-obj%04d.png", frameNumber, objId));
				Sam2Images.writePng(image, target);

				JsonObject record = new JsonObject()
					.put("index", records.size())
					.put("path", target.toString())
					.put("frame", frameNumber)
					.put("objId", objId)
					.put("area", mask.getInteger("area", 0))
					.put("bbox", mask.getJsonObject("bbox"));
				copyIfPresent(mask, record, "label");
				records.add(record);
			}
		}

		JsonObject truncated = response.getJsonObject("truncated", new JsonObject());
		JsonObject manifest = manifest(response, Sam2Mode.TRACK, records, dir, null,
			response.getInteger("width", frames.sampledWidth()), response.getInteger("height", frames.sampledHeight()),
			frames.nativeWidth(), frames.nativeHeight(),
			truncated.getInteger("masks", 0), frames.capped() ? 1 : truncated.getInteger("frames", 0));
		manifest.put("frameCount", responseFrames.size());
		manifest.put("objects", response.getJsonArray("objects", new JsonArray()));
		return manifest;
	}

	/**
	 * Turn upstream boxes into track prompts, snapping each to the nearest sampled frame.
	 *
	 * <p>
	 * The upstream detector samples at <em>its</em> chop rate, so a box stamped frame 137 need not be
	 * one of the frames sampled here. Both the frame it asked for and the frame it got are recorded,
	 * so a mask that starts a little late is explicable rather than mysterious. Dropping the box
	 * instead would silently lose an object.
	 * </p>
	 */
	private List<Sam2TrackPrompt> trackPrompts(SampledFrames frames, List<UpstreamBox> boxes) {
		double scale = frames.scaleToSampled();
		List<Sam2TrackPrompt> prompts = new ArrayList<>();
		int objId = 1;
		for (UpstreamBox box : boxes) {
			Sam2Box prompt = box.toPrompt(scale, frames.nativeWidth(), frames.nativeHeight(), objId);
			if (!prompt.isValid()) {
				continue;
			}
			int index = frames.nearestIndex(box.frame());
			if (index < 0) {
				continue;
			}
			prompts.add(new Sam2TrackPrompt(objId++, index, prompt));
		}
		if (prompts.isEmpty() && boxes.isEmpty()) {
			// TRACK with nothing wired is SAM 2's own demo shape: prompt the whole first frame and let
			// propagation carry it. One box covering the frame is what "everything here" means to a
			// box-prompted predictor.
			prompts.add(new Sam2TrackPrompt(1, Math.min(options().getTrackFrame(), frames.size() - 1),
				new Sam2Box(0, 0, frames.sampledWidth(), frames.sampledHeight(), null, 1)));
		}
		return prompts;
	}

	// ────────────────────────────────────────────────────────────────────────
	// Upstream detections
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * One upstream detection element, reduced to what a prompt needs.
	 *
	 * @param x          left edge, in the space named by {@code imageWidth}/{@code imageHeight}
	 * @param y          top edge
	 * @param width      box width
	 * @param height     box height
	 * @param label      the upstream class name, carried onto the produced mask
	 * @param frame      the source frame the box was measured on
	 * @param imageWidth width the box was measured against, 0 when the element did not say
	 * @param imageHeight height it was measured against
	 */
	private record UpstreamBox(double x, double y, double width, double height, String label, int frame,
		int imageWidth, int imageHeight) {

		/**
		 * Convert to a prompt in the posted image's space.
		 *
		 * @param scale       posted size over source size
		 * @param nativeWidth the width the caller actually posted against, used to re-base a box that
		 *                    was measured against a differently sized frame
		 */
		Sam2Box toPrompt(double scale, int nativeWidth, int nativeHeight, Integer objId) {
			double rebase = 1.0d;
			// A box measured against a differently-sized frame than the one we sampled - a detector run
			// with its own videoScaleSize, say - has to be re-based before it can be scaled.
			if (imageWidth > 0 && nativeWidth > 0 && imageWidth != nativeWidth) {
				rebase = (double) nativeWidth / (double) imageWidth;
			}
			double factor = scale * rebase;
			return Sam2Box.fromXywh(x, y, width, height, factor, label, objId);
		}

		/** Stable material for the cache digest — the geometry that will shape the masks. */
		String material() {
			return Math.round(x) + "," + Math.round(y) + "," + Math.round(width) + "," + Math.round(height) + "@" + frame;
		}
	}

	/**
	 * Read every wired detection element.
	 *
	 * <p>
	 * The element states its own coordinate convention, so this path is unambiguous — but it does not
	 * always state it. The {@code NORMALIZED} guard is {@code SceneLayoutNode.readElement}'s, copied
	 * because the same producers feed both nodes.
	 * </p>
	 */
	private List<UpstreamBox> readDetections(NodeContext<LoomMedia> ctx) {
		List<UpstreamBox> boxes = new ArrayList<>();
		for (Element<String> element : ctx.inputs(IN_DETECTIONS)) {
			JsonObject item = new JsonObject(element.value());
			JsonObject bbox = item.getJsonObject("bbox");
			if (bbox == null) {
				continue;
			}
			double x = bbox.getDouble("x", 0d);
			double y = bbox.getDouble("y", 0d);
			double width = bbox.getDouble("w", 0d);
			double height = bbox.getDouble("h", 0d);
			int imageWidth = item.getInteger("imageWidth", 0);
			int imageHeight = item.getInteger("imageHeight", 0);
			if ("NORMALIZED".equals(item.getString("coordinates")) && imageWidth > 0 && imageHeight > 0) {
				x *= imageWidth;
				width *= imageWidth;
				y *= imageHeight;
				height *= imageHeight;
			}
			String type = item.getString("type", "object");
			boxes.add(new UpstreamBox(x, y, width, height, item.getString("label", type),
				item.getInteger("frame", 0), imageWidth, imageHeight));
		}
		return boxes;
	}

	/** Convert upstream boxes into still-image prompts, dropping any that enclose no area. */
	private List<Sam2Box> toPrompts(List<UpstreamBox> boxes, double scale, int sourceWidth, int sourceHeight) {
		List<Sam2Box> prompts = new ArrayList<>(boxes.size());
		for (UpstreamBox box : boxes) {
			Sam2Box prompt = box.toPrompt(scale, sourceWidth, sourceHeight, null);
			if (prompt.isValid()) {
				prompts.add(prompt);
			}
		}
		return prompts;
	}

	// ────────────────────────────────────────────────────────────────────────
	// Output
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * Assemble the manifest: the metadata a consumer needs, and the record of every mask written.
	 *
	 * <p>
	 * Both dimension pairs travel. {@code width}/{@code height} describe the masks; {@code imageWidth}/
	 * {@code imageHeight} describe the source they came from. Carrying both is what lets a consumer
	 * project a mask back onto the original — without them the projection is a guess.
	 * </p>
	 */
	private JsonObject manifest(JsonObject response, Sam2Mode mode, JsonArray records, Path dir, Path overlay,
		int width, int height, int sourceWidth, int sourceHeight, int droppedMasks, int droppedFrames) {
		JsonObject manifest = new JsonObject()
			.put("model", response.getString("model"))
			.put("mode", mode.name())
			.put("dir", dir.toString())
			.put("width", width)
			.put("height", height)
			.put("imageWidth", sourceWidth)
			.put("imageHeight", sourceHeight)
			.put("masks", records)
			.put("truncated", new JsonObject().put("masks", droppedMasks).put("frames", droppedFrames));
		if (overlay != null) {
			manifest.put("overlay", overlay.toString());
		}
		return manifest;
	}

	/**
	 * Write the ports from the manifest, then commit the directory by writing the manifest itself.
	 *
	 * <p>
	 * The manifest lands last on purpose: it is the marker the cache checks, so it must not exist
	 * until every file it names does.
	 * </p>
	 */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject manifest) {
		JsonArray masks = manifest.getJsonArray("masks", new JsonArray());
		JsonObject truncated = manifest.getJsonObject("truncated", new JsonObject());
		boolean capped = truncated.getInteger("masks", 0) > 0 || truncated.getInteger("frames", 0) > 0;

		ctx.output(OUT_MASK_COUNT, (long) masks.size());
		ctx.output(OUT_FLAG, masks.isEmpty() ? FLAG_NONE : (capped ? FLAG_CAPPED : FLAG_SUCCESS));
		ctx.output(OUT_SEGMENTS, manifest.encode());
		for (int i = 0; i < masks.size(); i++) {
			ctx.outputElement(OUT_MASKS, masks.getJsonObject(i).getString("path"));
		}
		if (manifest.getString("overlay") != null) {
			ctx.output(OUT_OVERLAY, manifest.getString("overlay"));
		}
		ctx.preview(OUT_SEGMENTS, masksMarkdown(masks));

		writeManifest(manifest);
	}

	/**
	 * Persist the manifest into its artifact directory.
	 *
	 * <p>
	 * Best-effort in the same sense a preview is: the masks are already on disk and the ports are
	 * already emitted, so a manifest that cannot be written costs a future cache hit, not this run.
	 * </p>
	 */
	private void writeManifest(JsonObject manifest) {
		Path dir = Path.of(manifest.getString("dir"));
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(MANIFEST_NAME), manifest.encodePrettily(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("Could not write the sam2 manifest to {}: {}", dir, e.getMessage());
		}
	}

	/**
	 * Describe the masks as a table, for the run debugging view.
	 *
	 * <p>
	 * The default rendering shows each element as the path it is, which says nothing about what was
	 * segmented. A column of labels next to a column of areas answers "what did it find" at a glance.
	 * </p>
	 */
	private static String masksMarkdown(JsonArray masks) {
		if (masks.isEmpty()) {
			return "No masks produced.";
		}
		StringBuilder md = new StringBuilder("| # | label | area | score | box (x, y, w, h) | frame |\n|---|---|---|---|---|---|\n");
		for (int i = 0; i < masks.size(); i++) {
			try {
				JsonObject mask = masks.getJsonObject(i);
				JsonObject box = mask.getJsonObject("bbox", new JsonObject());
				md.append("| ").append(mask.getValue("index", "")).append(" | ")
					.append(mask.getValue("label", "")).append(" | ")
					.append(mask.getValue("area", "")).append(" | ")
					.append(mask.getValue("score", "")).append(" | ")
					.append(box.getValue("x", "")).append(", ").append(box.getValue("y", "")).append(", ")
					.append(box.getValue("w", "")).append(", ").append(box.getValue("h", "")).append(" | ")
					.append(mask.getValue("frame", "")).append(" |\n");
			} catch (Exception e) {
				// A preview must never be able to fail the node that produced the real masks.
				return "Could not render " + masks.size() + " masks.";
			}
		}
		return md.toString();
	}

	/**
	 * Attach one preview per mask, cut from the frame they were measured on.
	 *
	 * <p>
	 * Needed because {@code NodePreviews} downsamples only the <em>first</em> element of a
	 * {@code MANY} port, so the masks port would render a segment-everything run as a single picture.
	 * The overlay is the honest whole-result view; these are the per-object detail.
	 * </p>
	 */
	private void previewMasks(NodeContext<LoomMedia> ctx, List<BufferedImage> masks) {
		if (!ctx.capturePreviews() || masks.isEmpty()) {
			return;
		}
		for (int i = 0; i < masks.size(); i++) {
			ctx.preview(OUT_MASKS, i, ImagePreviews.fromImage(masks.get(i), MASK_PREVIEW_EDGE_PX));
		}
	}

	/**
	 * Composite and write the overlay, or return null when it is switched off or there is nothing to
	 * show.
	 */
	private Path writeOverlay(BufferedImage frame, List<BufferedImage> masks, Path dir) throws IOException {
		if (!options().isEmitOverlay() || masks.isEmpty()) {
			return null;
		}
		Path target = dir.resolve("overlay.png");
		Sam2Images.writePng(Sam2Images.overlay(frame, masks), target);
		return target;
	}

	// ────────────────────────────────────────────────────────────────────────
	// Paths, digests, plumbing
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * The artifact directory: {@code metaPath/sam2_bin/<segment>/<sha512>-<digest>/}.
	 *
	 * <p>
	 * A directory rather than a family of flat file names, because a segment-everything run writes
	 * dozens of masks and both the cache check and any cleanup then cost one path operation instead of
	 * N.
	 * </p>
	 *
	 * <p>
	 * The digest is in the path, not only in the cache key. Two {@code sam2} nodes in one graph — an
	 * automatic pass and a {@code person}-only prompted pass — must not overwrite each other's masks,
	 * which is the lesson {@code image-manipulation} records.
	 * </p>
	 */
	private Path artifactDir(LoomMedia media, String digest) {
		SHA512 hash = media.getSHA512();
		Path basePath = cortexOptions.getMetaPath().resolve("sam2_bin");
		return HashUtils.segmentPath(basePath, hash).resolve(hash + "-" + digest);
	}

	/**
	 * A short digest of everything that changes the produced masks — the options <em>and</em> the
	 * prompt boxes.
	 *
	 * <p>
	 * The boxes matter as much as the options: without them a re-run against better detections would
	 * be served the first run's masks from the local cache, and would collide with them on disk too.
	 * </p>
	 */
	private String digest(List<UpstreamBox> boxes) {
		Sam2NodeOptions o = options();
		StringBuilder material = new StringBuilder()
			.append(o.getModel()).append('|')
			.append(o.getMode()).append('|')
			.append(o.getMaxDim()).append('|')
			.append(o.getPointsPerSide()).append('|')
			.append(o.getPredIouThresh()).append('|')
			.append(o.getStabilityScoreThresh()).append('|')
			.append(o.getMinMaskArea()).append('|')
			.append(o.getMaxMasks()).append('|')
			.append(o.isMultimask()).append('|')
			.append(o.getVideoChopRate()).append('|')
			.append(o.getMaxFrames()).append('|')
			.append(o.getTrackFrame()).append('|')
			.append(o.isEmitOverlay()).append('|');
		for (UpstreamBox box : boxes) {
			material.append(box.material()).append(';');
		}
		return sha256Hex(material.toString()).substring(0, 12);
	}

	/**
	 * {@code sam2/1:<checkpoint>} — which checkpoint produced a mask materially changes its edges, so
	 * the ledger records the model rather than merely that the node ran.
	 */
	private String producerVersion(String model) {
		String resolved = model != null && !model.isBlank() ? model : options().getModel();
		return "sam2/1:" + (resolved == null || resolved.isBlank() ? "default" : resolved);
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
		}
	}

	private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
		Object value = from.getValue(key);
		if (value != null) {
			to.put(key, value);
		}
	}

	/** Time a sidecar call and record it, so a failure is counted rather than only logged. */
	private JsonObject call(SidecarCall call) throws IOException {
		long start = System.currentTimeMillis();
		try {
			JsonObject result = call.invoke();
			metrics.recordAiCall("sam2", true, System.currentTimeMillis() - start);
			return result;
		} catch (RuntimeException | IOException e) {
			metrics.recordAiCall("sam2", false, System.currentTimeMillis() - start);
			throw e;
		}
	}

	@FunctionalInterface
	private interface SidecarCall {
		JsonObject invoke() throws IOException;
	}
}
