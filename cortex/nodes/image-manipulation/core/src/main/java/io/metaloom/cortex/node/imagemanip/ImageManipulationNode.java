package io.metaloom.cortex.node.imagemanip;

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
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
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
import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Size;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Applies an ordered chain of geometric operations to an image and writes the result as a new artifact.
 *
 * <p>
 * EXIF autorotation, a fixed crop, a subject-aware crop driven by upstream detections, aspect-ratio normalisation - including the blurred-pad fix for
 * vertical-video framing - and a bounding resize, all in a single decode, transform and encode pass. <strong>The source file is never modified.</strong>
 * </p>
 *
 * <p>
 * The autorotation is not a convenience. The {@code metadata} node records EXIF {@code Orientation} on the asset, but nothing in the tree applies it
 * and {@code ImageIO} ignores it by design, so every other image node measures a phone photo sideways. This is the node that fixes the pixels.
 * </p>
 *
 * <p>
 * 🔴 The operations are not independent, which is why they live in one node rather than five. Autorotation redefines the coordinate space that crop
 * rectangles <em>and</em> the upstream detection boxes are expressed in, so the boxes are carried through the same transform as the pixels - and
 * through every later crop, pad and resize as well. Split across nodes, a subject crop after a rotation would frame a region that is plausible and
 * wrong, silently.
 * </p>
 *
 * <p>
 * Following the {@code watermark}/{@code thumbnail}/{@code depthmap}/{@code imagegen} contract the result is written to a worker-local cache under
 * {@code metaPath/imagemanip_bin} and only the {@code asset_node_result} ledger entry is recorded in Loom. Wire the {@code image} output into
 * {@code s3-sink} to keep the bytes.
 * </p>
 */
@NodeSpec(nodeId = "image-manipulation", name = "Image Manipulation", icon = "crop", category = NodeCategory.TRANSFORM,
	description = "Straighten, crop and reframe an image in one pass - apply its EXIF orientation, cut a fixed or subject-aware window, "
		+ "force a target aspect ratio (padding the margins with a blurred enlargement rather than black bars), and bound the result's "
		+ "long edge. The source file is never modified; the new image is written to the worker's local cache, so wire it into a sink to keep it.",
	defaultConcurrency = 4)
public class ImageManipulationNode extends AbstractMediaNode<ImageManipulationNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ImageManipulationNode.class);

	public static final String KIND = "image-manipulation";

	/** Bumped when an operation's meaning changes, so a ledger row from an older build is visibly a different producer. */
	public static final String ALGORITHM_VERSION = "image-manipulation/1";

	@PortDoc(label = "Image", description = "The image to transform. Video and documents are skipped", order = 10)
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	/**
	 * The subjects a {@code SUBJECT_CROP} frames, normally wired from {@code facedetect}.
	 *
	 * <p>
	 * <strong>{@code MANY}, so the node gathers the boxes and produces one composed crop per image.</strong> Declared {@code ONE} it would instead run
	 * once per face and emit one crop each - per-person thumbnails, which is a different feature and would need the face index in the artifact name.
	 * </p>
	 *
	 * <p>
	 * Optional: every operation except {@code SUBJECT_CROP} works with nothing wired here, and marking it required would make the editor suggest a
	 * facedetect dependency that a plain autorotate pipeline does not have.
	 * </p>
	 */
	@PortDoc(label = "Detections", required = false,
		description = "Boxes for SUBJECT_CROP to frame, usually from facedetect. Leave unwired for the other operations", order = 20)
	public static final InputPort<String> IN_DETECTIONS = InputPort.many("detections", ContentTypeRegistry.DETECTION_ANY, String.class);

	@PortDoc(label = "Image", description = "The transformed image, written to the worker's artifact cache", order = 10)
	public static final OutputPort<String> OUT_IMAGE = OutputPort.one("image", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Geometry",
		description = "What was actually done: the source and result dimensions, the orientation applied, and each operation's rectangle", order = 20)
	public static final OutputPort<String> OUT_GEOMETRY = OutputPort.one("geometry", ContentTypeRegistry.STRUCT_JSON, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item", order = 30)
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	private static final int RESULT_CACHE_SIZE = 10_000;

	/** What a completed run produced, held for this worker's lifetime. Non-durable; the ledger row in Loom is the durable record. */
	private record CachedResult(String artifact, String geometry) {
	}

	private final LocalResultCache<CachedResult> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final CortexOptions cortexOptions;

	@Inject
	public ImageManipulationNode(@Nullable LoomClient client, CortexOptions cortexOptions, ImageManipulationNodeOptions options) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		List<Op> chain = options().operationChain();

		BufferedImage source = MediaArtifacts.decodedImage(ctx);

		// Parsed before the cache is consulted: the boxes are a second input that changes the output
		// pixels, so they belong in the key. Only the boxes that survive filtering are digested - one
		// that was dropped cannot change the result and must not invalidate the cache.
		List<Rect> boxes = chain.contains(Op.SUBJECT_CROP)
			? SubjectBoxes.parse(detections(ctx), options().subjectTypeSet(), options().getMinConfidence(), source.getWidth(), source.getHeight())
			: List.of();

		String digest = digest(boxes);
		String cacheKey = media.absolutePath() + "|" + digest;

		CachedResult cached = resultCache.get(cacheKey);
		if (cached != null && Files.exists(Path.of(cached.artifact()))) {
			// The file is re-checked, not just the key: an artifact deleted from the cache directory between
			// runs would otherwise be handed downstream as a path that no longer resolves.
			emit(ctx, "DONE", cached.artifact(), cached.geometry());
			return ctx.origin(LOCAL).next();
		}

		try {
			Frame frame = new Frame(source, boxes);
			JsonObject applied = new JsonObject()
				.put("sourceWidth", source.getWidth())
				.put("sourceHeight", source.getHeight())
				.put("subjects", boxes.size());
			JsonArray steps = new JsonArray();

			for (Op op : chain) {
				Skip skip = frame.apply(op, options(), media, steps);
				if (skip != null) {
					ctx.print("SKIPPED", skip.reason());
					ctx.output(OUT_FLAG, "SKIPPED");
					return ctx.skipped(skip.reason()).next();
				}
			}

			applied.put("operations", steps)
				.put("resultWidth", frame.image().getWidth())
				.put("resultHeight", frame.image().getHeight())
				.put("format", options().getOutputFormat().name());

			Path target = resolveArtifactPath(media, digest);
			ManipulationImages.write(frame.image(), target, options().getOutputFormat(), options().getJpegQuality(),
				ManipulationGeometry.parseColor(options().getBackgroundColor()));

			String geometry = applied.encode();
			ctx.print("DONE", frame.image().getWidth() + "x" + frame.image().getHeight() + ", " + Files.size(target) + " bytes");
			emit(ctx, "DONE", target.toString(), geometry);
			resultCache.put(cacheKey, new CachedResult(target.toString(), geometry));

			// Ledger only: the bytes live in the local imagemanip_bin cache. Loom's byte-ingest route
			// (POST /api/v1/attachments) exists but has no attachment type for a derived rendition yet - see
			// the node's spec for what that needs.
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to manipulate image {}", media.absolutePath(), e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			// abort(), not next(): NodeContextImpl.next() ignores the recorded failure cause and would
			// report this run as SUCCESS with no artifact.
			return ctx.failure(e.getMessage()).abort();
		}
	}

	private void emit(NodeContext<LoomMedia> ctx, String flag, String artifact, String geometry) {
		ctx.output(OUT_FLAG, flag);
		ctx.output(OUT_IMAGE, artifact);
		ctx.output(OUT_GEOMETRY, geometry);
	}

	private List<String> detections(NodeContext<LoomMedia> ctx) {
		return ctx.inputs(IN_DETECTIONS).stream().map(Element::value).toList();
	}

	/** A deliberate, non-exceptional early exit - {@code SUBJECT_CROP} with {@code subjectFallback = SKIP} and nothing detected. */
	private record Skip(String reason) {
	}

	/**
	 * The image and its subject boxes, moving through the chain together.
	 *
	 * <p>
	 * 🔴 Keeping the two in one place is the entire reason the operations compose correctly. Every step that moves the frame's origin - a crop, a pad -
	 * or changes its scale has to move the boxes by the same amount, or a later {@code SUBJECT_CROP} frames coordinates from a frame that no longer
	 * exists.
	 * </p>
	 */
	private static final class Frame {

		private BufferedImage image;

		private List<Rect> boxes;

		private Frame(BufferedImage image, List<Rect> boxes) {
			this.image = image;
			this.boxes = boxes;
		}

		private BufferedImage image() {
			return image;
		}

		/**
		 * Apply one operation to both the pixels and the boxes.
		 *
		 * @return a {@link Skip} when the item should stop here, or null to continue
		 */
		private Skip apply(Op op, ImageManipulationNodeOptions options, LoomMedia media, JsonArray steps) {
			return switch (op) {
				case AUTOROTATE -> autorotate(media, steps);
				case CROP -> {
					Rect rect = ManipulationGeometry.relativeCrop(image.getWidth(), image.getHeight(),
						options.getCropX(), options.getCropY(), options.getCropWidth(), options.getCropHeight());
					cropTo(rect, "CROP", steps);
					yield null;
				}
				case SUBJECT_CROP -> subjectCrop(options, steps);
				case ASPECT -> {
					aspect(options, steps);
					yield null;
				}
				case RESIZE -> {
					resize(options, steps);
					yield null;
				}
			};
		}

		private Skip autorotate(LoomMedia media, JsonArray steps) {
			Orientation orientation = ExifOrientation.read(media.file());
			int width = image.getWidth();
			int height = image.getHeight();
			image = ManipulationImages.orient(image, orientation);
			boxes = boxes.stream().map(box -> ManipulationGeometry.transform(orientation, box, width, height)).toList();
			steps.add(new JsonObject()
				.put("op", Op.AUTOROTATE.name())
				.put("orientation", orientation.name())
				.put("exif", orientation.exifValue())
				.put("width", image.getWidth())
				.put("height", image.getHeight()));
			return null;
		}

		private Skip subjectCrop(ImageManipulationNodeOptions options, JsonArray steps) {
			double aspect = ManipulationGeometry.parseAspect(options.getTargetAspect());
			if (boxes.isEmpty()) {
				return switch (options.getSubjectFallback()) {
					case SKIP -> new Skip("no subject detected");
					case FAIL -> throw new IllegalStateException("No subject detected and subjectFallback is FAIL");
					case CENTRE -> {
						cropTo(ManipulationGeometry.centreAspect(image.getWidth(), image.getHeight(), aspect), "SUBJECT_CROP", steps);
						yield null;
					}
				};
			}
			Rect padded = ManipulationGeometry.pad(ManipulationGeometry.union(boxes), options.getSubjectPadding());
			cropTo(ManipulationGeometry.expandToAspect(padded, aspect, image.getWidth(), image.getHeight()), "SUBJECT_CROP", steps);
			return null;
		}

		private void aspect(ImageManipulationNodeOptions options, JsonArray steps) {
			double aspect = ManipulationGeometry.parseAspect(options.getTargetAspect());
			if (aspect <= 0d) {
				return;
			}
			if (options.getAspectMode() == AspectMode.CROP) {
				cropTo(ManipulationGeometry.centreAspect(image.getWidth(), image.getHeight(), aspect), "ASPECT", steps);
				return;
			}
			Size canvas = ManipulationGeometry.padToAspect(image.getWidth(), image.getHeight(), aspect);
			int dx = (canvas.w() - image.getWidth()) / 2;
			int dy = (canvas.h() - image.getHeight()) / 2;
			image = options.getPadFill() == PadFill.BLUR
				? ManipulationImages.padWithBlur(image, canvas, options.getBlurRadius(), options.getBlurZoom())
				: ManipulationImages.padWithColor(image, canvas, ManipulationGeometry.parseColor(options.getPadColor()));
			boxes = boxes.stream().map(box -> ManipulationGeometry.translate(box, dx, dy)).toList();
			steps.add(new JsonObject()
				.put("op", Op.ASPECT.name())
				.put("mode", AspectMode.PAD.name())
				.put("fill", options.getPadFill().name())
				.put("width", canvas.w())
				.put("height", canvas.h()));
		}

		private void resize(ImageManipulationNodeOptions options, JsonArray steps) {
			Size bounds = ManipulationGeometry.resizeBounds(image.getWidth(), image.getHeight(), options.getMaxLongEdge(), options.isAllowUpscale());
			if (bounds.w() == image.getWidth() && bounds.h() == image.getHeight()) {
				return;
			}
			double sx = (double) bounds.w() / image.getWidth();
			double sy = (double) bounds.h() / image.getHeight();
			image = ManipulationImages.resize(image, bounds);
			boxes = boxes.stream().map(box -> ManipulationGeometry.scale(box, sx, sy)).toList();
			steps.add(new JsonObject().put("op", Op.RESIZE.name()).put("width", bounds.w()).put("height", bounds.h()));
		}

		/** Cut the window and rebase the boxes onto it, dropping any that the cut removed entirely. */
		private void cropTo(Rect rect, String label, JsonArray steps) {
			image = ManipulationImages.crop(image, rect);
			List<Rect> moved = new ArrayList<>();
			for (Rect box : boxes) {
				Rect shifted = ManipulationGeometry.translate(box, -rect.x(), -rect.y());
				if (ManipulationGeometry.intersects(shifted, rect.w(), rect.h())) {
					moved.add(ManipulationGeometry.clamp(shifted, rect.w(), rect.h()));
				}
			}
			boxes = moved;
			steps.add(new JsonObject()
				.put("op", label)
				.put("x", rect.x()).put("y", rect.y())
				.put("width", rect.w()).put("height", rect.h()));
		}
	}

	/**
	 * The artifact path: {@code metaPath/imagemanip_bin/<segment>/<sha512>-<digest>.<ext>}.
	 *
	 * <p>
	 * The digest is in the file name, not only in the cache key. Two {@code image-manipulation} nodes in one graph - a 16:9 hero crop and a 1:1
	 * thumbnail, say - key on the same media SHA-512 and would otherwise write to the same path and each serve the other's output.
	 * </p>
	 */
	private Path resolveArtifactPath(LoomMedia media, String digest) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + "-" + digest + options().getOutputFormat().extension();
		Path basePath = cortexOptions.getMetaPath().resolve("imagemanip_bin");
		return HashUtils.segmentPath(basePath, hash).resolve(fileName);
	}

	/**
	 * A short digest of everything that changes the output pixels - the options <em>and</em> the surviving subject boxes.
	 *
	 * <p>
	 * The boxes matter as much as the options: without them a re-run against better face detections would be served the first run's crop from the local
	 * cache, and the file name would collide with it too.
	 * </p>
	 */
	private String digest(List<Rect> boxes) {
		ImageManipulationNodeOptions o = options();
		String material = String.join("|",
			String.valueOf(o.getOperations()),
			Double.toString(o.getCropX()), Double.toString(o.getCropY()),
			Double.toString(o.getCropWidth()), Double.toString(o.getCropHeight()),
			String.valueOf(o.getSubjectTypes()), Double.toString(o.getMinConfidence()),
			Double.toString(o.getSubjectPadding()), String.valueOf(o.getSubjectFallback()),
			String.valueOf(o.getTargetAspect()), String.valueOf(o.getAspectMode()), String.valueOf(o.getPadFill()),
			String.valueOf(o.getPadColor()), Integer.toString(o.getBlurRadius()), Double.toString(o.getBlurZoom()),
			Integer.toString(o.getMaxLongEdge()), Boolean.toString(o.isAllowUpscale()),
			String.valueOf(o.getOutputFormat()), Double.toString(o.getJpegQuality()), String.valueOf(o.getBackgroundColor()),
			SubjectBoxes.material(boxes));
		return sha256Hex(material).substring(0, 12);
	}

	/** {@code image-manipulation/1:<digest of the chain>} - a reframed asset records which framing produced it, not merely that some node ran. */
	private String producerVersion() {
		return ALGORITHM_VERSION + ":" + sha256Hex(String.valueOf(options().getOperations()) + "|" + options().getTargetAspect()).substring(0, 12);
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the JLS for every conformant JRE.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/** Exposed for the tests, which assert the accepted-type parsing without going through a whole run. */
	static Set<String> acceptedTypes(ImageManipulationNodeOptions options) {
		return options.subjectTypeSet();
	}
}
