package io.metaloom.cortex.node.scenelayout;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Scene-layout node. Joins detector bounding boxes to a depth map and derives how the detected
 * objects relate: which depth band each sits in, and pairwise relations such as
 * {@code IN_FRONT_OF}, {@code OCCLUDES}, {@code LEFT_OF} and {@code NEXT_TO}.
 *
 * <p>
 * There is no model and no sidecar - this is geometry and statistics over the boxes and the map.
 * The interesting logic lives in {@link DepthSampler} and {@link RelationSolver}, which are pure
 * and therefore testable with synthetic depth gradients rather than a GPU.
 * </p>
 *
 * <h2>Inputs</h2>
 *
 * <p>
 * Depth arrives on the {@link #IN_DEPTH} port and boxes on {@link #IN_DETECTIONS}; when no
 * detector is wired the node falls back to {@code listAssetDetections} over REST. The wired path is
 * preferred because its payload carries an explicit coordinate marker; the REST path has to apply
 * a heuristic (see {@link #readFromLoom}) because the {@code detection} table's own geometry
 * convention is ambiguous.
 * </p>
 *
 * <p>
 * Both used to be addressed by node id - a {@code depthNodeId} option defaulting to
 * {@code "depthmap"} and a {@code detectionSources} list defaulting to {@code ["facedetect"]}.
 * Renaming either node in the editor turned this node into a permanent "no depth map" skip, which
 * reads like a depth-node problem and is not.
 * </p>
 *
 * <p>
 * 🔴 The depth map is a worker-local artifact, so this node must run on the same worker as the
 * {@code depthmap} node that produced it. Pin both into one affinity group in the pipeline
 * definition; otherwise this node lands elsewhere and skips with "depth map not found", which
 * looks like a depth-node problem and is not.
 * </p>
 *
 * <p>
 * A missing input is a <em>skip</em>, not a failure: a photo with no faces in it is a normal
 * outcome, and reporting FAILED for it would block blocking downstream nodes and pollute the run
 * summary.
 * </p>
 */
@NodeSpec(nodeId = "scene-layout", name = "Scene Layout", icon = "schema", category = NodeCategory.ANALYSIS,
	description = "Relate detected objects to one another using a depth map: foreground/background bands plus pairwise relations such as "
		+ "in front of, behind, occludes and next to. Requires an upstream depthmap node on the same worker.",
	// No model and no device to contend for - only CPU, so this can run wider than the model-backed nodes.
	defaultConcurrency = 4)
public class SceneLayoutNode extends AbstractMediaNode<SceneLayoutNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(SceneLayoutNode.class);

	/** The depth metadata, which also carries the worker-local path of the map itself. */
	@PortDoc(label = "Depth Metadata", description = "Depth metadata from an upstream depthmap node sharing this node's affinity group")
	public static final InputPort<String> IN_DEPTH = InputPort.one("depth", ContentTypeRegistry.STRUCT_DEPTHMAP, String.class);

	/** Every detected box to place, from any detector - the node only needs a box and a label. */
	@PortDoc(label = "Detections", description = "The boxes to place in the scene - faces, objects or plain regions")
	public static final InputPort<String> IN_DETECTIONS = InputPort.many("detections", ContentTypeRegistry.DETECTION_ANY, String.class);

	@PortDoc(label = "Scene Layout", description = "Depth band per object plus pairwise relations such as in front of, occludes and next to")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_SCENE_LAYOUT, String.class);

	@PortDoc(label = "Object Count", description = "How many boxes were placed in the scene")
	public static final OutputPort<Long> OUT_OBJECT_COUNT = OutputPort.one("object_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Relation Count", description = "How many pairwise relations were emitted")
	public static final OutputPort<Long> OUT_RELATION_COUNT = OutputPort.one("relation_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/** The depth convention this node understands. Larger = nearer to the camera. */
	public static final String CONVENTION_NEARNESS = "NEARNESS";

	/** In-heap skip cache of the result JSON, keyed by media path. Non-durable - the durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	@Inject
	public SceneLayoutNode(@Nullable LoomClient client, CortexOptions cortexOptions, SceneLayoutNodeOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "scene-layout";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		// Video would need per-frame layout, which is blocked on per-keyframe depth.
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		String path = ctx.media().absolutePath();

		String cached = resultCache.get(path);
		if (cached != null) {
			JsonObject payload = new JsonObject(cached);
			emit(ctx, payload);
			return ctx.origin(LOCAL).next();
		}

		try {
			JsonObject depthMeta = readDepthMeta(ctx);
			if (depthMeta == null) {
				return ctx.skipped("no depth map").next();
			}

			String convention = depthMeta.getString("convention");
			if (!CONVENTION_NEARNESS.equals(convention)) {
				// Refuse rather than guess: every ordering below depends on knowing which way "closer" runs.
				return ctx.skipped("unsupported depth convention '" + convention + "'").next();
			}

			File mapFile = new File(depthMeta.getString("path"));
			if (!mapFile.exists()) {
				// Almost always an affinity mistake - this node ran on a different worker to the one that produced the map.
				log.warn("Depth map {} does not exist on this worker; is scene-layout in the same affinity group as the depthmap node?", mapFile);
				return ctx.skipped("depth map file not found").next();
			}

			List<RawDetection> raw = readDetections(ctx, asset);
			if (raw.isEmpty()) {
				return ctx.skipped("no detections").next();
			}
			if (raw.size() < 2) {
				// One object has nothing to relate to. Its band alone is not worth a component row.
				return ctx.skipped("only one detection - nothing to relate").next();
			}

			int imageWidth = depthMeta.getInteger("imageWidth", 0);
			int imageHeight = depthMeta.getInteger("imageHeight", 0);
			DepthMap map = DepthMap.read(mapFile, imageWidth, imageHeight);

			int droppedObjects = 0;
			if (raw.size() > options().getMaxObjects()) {
				// Keep the largest boxes - a cap that dropped the subject would be worse than useless.
				raw.sort(Comparator.comparingDouble((RawDetection d) -> d.box().area()).reversed());
				droppedObjects = raw.size() - options().getMaxObjects();
				raw = new ArrayList<>(raw.subList(0, options().getMaxObjects()));
				log.info("Capped scene-layout objects at {} for {}, dropped {}", options().getMaxObjects(), path, droppedObjects);
			}

			List<LayoutObject> objects = new ArrayList<>();
			int unsampled = 0;
			for (RawDetection d : raw) {
				BoxF mapBox = map.projectFromImage(d.box());
				DepthStats stats = DepthSampler.sample(map, mapBox, options().getCoreInset(), options().getMinCorePixels());
				if (stats == null) {
					unsampled++;
					continue;
				}
				objects.add(new LayoutObject(d.id(), d.label(), d.type(), d.source(), d.box(), mapBox, d.confidence(), stats, null));
			}
			if (unsampled > 0) {
				log.info("Dropped {} detection(s) for {} whose sampling core was under {} px", unsampled, path, options().getMinCorePixels());
			}
			if (objects.size() < 2) {
				return ctx.skipped("fewer than two measurable detections").next();
			}

			RelationSolver solver = new RelationSolver(options());
			objects = solver.assignBands(objects, map);
			List<SpatialRelation> all = solver.solve(objects);
			List<String> phrases = options().isEmitPhrases() ? solver.phrases(objects, all) : List.of();

			JsonObject payload = buildPayload(depthMeta, map, objects, all, phrases, droppedObjects, unsampled);
			emit(ctx, payload);
			resultCache.put(path, payload.encode());

			ctx.print("DONE", objects.size() + " objects, " + all.size() + " relations");
			persist(ctx, asset, payload, depthMeta.getString("model"));
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to compute scene layout for media {}", path, e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		ctx.output(OUT_RESULT, payload.encode());
		ctx.output(OUT_OBJECT_COUNT, (long) payload.getJsonArray("objects").size());
		ctx.output(OUT_RELATION_COUNT, (long) payload.getJsonArray("relations").size());
	}

	/**
	 * Read the depth metadata from the wired depth port.
	 */
	private JsonObject readDepthMeta(NodeContext<LoomMedia> ctx) {
		String meta = ctx.input(IN_DEPTH);
		if (meta == null) {
			return null;
		}
		JsonObject parsed = new JsonObject(meta);
		// The path travels inside the metadata; a payload without one cannot be opened.
		return parsed.getString("path") == null ? null : parsed;
	}

	/**
	 * Gather boxes, preferring the wired detections over the REST read-back.
	 */
	private List<RawDetection> readDetections(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		List<RawDetection> out = new ArrayList<>();
		for (Element<String> element : ctx.inputs(IN_DETECTIONS)) {
			RawDetection detection = readElement(new JsonObject(element.value()), element.seq());
			if (detection != null) {
				out.add(detection);
			}
		}
		if (!out.isEmpty()) {
			return out;
		}
		if (options().isAllowLoomFallback() && asset != null && client() != null) {
			return readFromLoom(asset);
		}
		return out;
	}

	/**
	 * Parse one detection element. This path is unambiguous: the element states its coordinate
	 * convention and the dimensions the box was measured against.
	 *
	 * @return the box, or null when the element carries none
	 */
	private RawDetection readElement(JsonObject item, int seq) {
		JsonObject bbox = item.getJsonObject("bbox");
		if (bbox == null) {
			return null;
		}
		BoxF box = new BoxF(
			bbox.getDouble("x", 0d),
			bbox.getDouble("y", 0d),
			bbox.getDouble("w", 0d),
			bbox.getDouble("h", 0d));
		int imageWidth = item.getInteger("imageWidth", 0);
		int imageHeight = item.getInteger("imageHeight", 0);
		if ("NORMALIZED".equals(item.getString("coordinates")) && imageWidth > 0 && imageHeight > 0) {
			box = box.scale(imageWidth, imageHeight);
		}
		String type = item.getString("type", "object");
		String label = item.getString("label", type);
		return new RawDetection(
			label + "-" + item.getInteger("index", seq),
			label, type, IN_DETECTIONS.id(), box,
			item.getDouble("confidence", 1d));
	}

	/**
	 * Read boxes back from Loom.
	 *
	 * <p>
	 * 🔴 This path has to guess the coordinate convention. {@code V2.43} documents
	 * {@code detection.bbox_x} as normalized 0-1, but {@code FacedetectNode} writes absolute
	 * pixels, {@code DetectionModelValidator} validates nothing, and no source dimensions are
	 * stored anywhere. The heuristic below - any component above 1.0 means the row is in pixels -
	 * is the best available reading, and it logs which branch it took rather than deciding
	 * silently. Prefer the upstream path.
	 * </p>
	 *
	 * <p>
	 * {@code DetectionResponse} also omits {@code label} and {@code detectionIndex}, so an object
	 * class cannot be recovered here - only {@code type}. That is fine for faces and is a
	 * prerequisite to fix before object detection lands.
	 * </p>
	 */
	private List<RawDetection> readFromLoom(AssetResponse asset) {
		List<RawDetection> out = new ArrayList<>();
		try {
			List<DetectionResponse> rows = client().listAssetDetections(asset.getUuid()).sync().body().getData();
			if (rows == null || rows.isEmpty()) {
				return out;
			}

			boolean pixels = rows.stream().anyMatch(r -> above(r.getBboxX()) || above(r.getBboxY())
				|| above(r.getBboxWidth()) || above(r.getBboxHeight()));
			log.info("Read {} detection(s) for asset {} from Loom, interpreting geometry as {}",
				rows.size(), asset.getUuid(), pixels ? "ABSOLUTE_PIXELS" : "NORMALIZED");
			if (!pixels) {
				// Normalized rows would need the source image size to become pixels, and nothing records it.
				log.warn("Detections for asset {} look normalized, but no source dimensions are stored - skipping the REST fallback", asset.getUuid());
				return out;
			}

			int index = 0;
			for (DetectionResponse row : rows) {
				String type = row.getType() != null ? row.getType() : "object";
				out.add(new RawDetection(
					type + "-" + index++,
					type, type, "loom",
					new BoxF(value(row.getBboxX()), value(row.getBboxY()), value(row.getBboxWidth()), value(row.getBboxHeight())),
					row.getConfidence() != null ? row.getConfidence() : 1d));
			}
		} catch (Exception e) {
			log.warn("Failed to read detections for asset {} from Loom: {}", asset.getUuid(), e.getMessage());
		}
		return out;
	}

	private static boolean above(Float value) {
		return value != null && value > 1.0f;
	}

	private static double value(Float value) {
		return value != null ? value : 0d;
	}

	/**
	 * Build the persisted payload. {@code truncated} is explicit rather than implied - a silently
	 * shortened result reads as "these are all the relations", which is worse than a short result
	 * you know is short.
	 */
	private JsonObject buildPayload(JsonObject depthMeta, DepthMap map, List<LayoutObject> objects,
		List<SpatialRelation> relations, List<String> phrases, int droppedObjects, int unsampled) {

		JsonArray objectArray = new JsonArray();
		for (LayoutObject o : objects) {
			objectArray.add(new JsonObject()
				.put("id", o.id())
				.put("label", o.label())
				.put("type", o.type())
				.put("source", o.source())
				.put("bbox", new JsonObject()
					.put("x", round(o.box().x()))
					.put("y", round(o.box().y()))
					.put("w", round(o.box().w()))
					.put("h", round(o.box().h())))
				.put("confidence", round(o.confidence()))
				.put("depth", new JsonObject()
					.put("near", round(o.depth().near()))
					.put("p25", round(o.depth().p25()))
					.put("p75", round(o.depth().p75()))
					.put("spread", round(o.depth().spread()))
					.put("pixels", o.depth().pixels())
					.put("band", o.band().name())));
		}

		JsonArray relationArray = new JsonArray();
		for (SpatialRelation r : relations) {
			relationArray.add(r.toJson());
		}

		JsonObject depth = new JsonObject()
			.put("model", depthMeta.getString("model"))
			.put("convention", depthMeta.getString("convention"))
			.put("source", depthMeta.getString("source"))
			.put("mapWidth", map.width())
			.put("mapHeight", map.height())
			.put("sceneQuantiles", new JsonObject()
				.put("background", round(map.sceneQuantile(options().getBackgroundQuantile())))
				.put("foreground", round(map.sceneQuantile(options().getForegroundQuantile()))));

		return new JsonObject()
			.put("image", new JsonObject()
				.put("width", depthMeta.getInteger("imageWidth", 0))
				.put("height", depthMeta.getInteger("imageHeight", 0)))
			.put("depth", depth)
			.put("objects", objectArray)
			.put("relations", relationArray)
			.put("phrases", new JsonArray(phrases))
			.put("truncated", new JsonObject()
				.put("objects", droppedObjects)
				.put("unsampled", unsampled)
				.put("relations", 0));
	}

	/**
	 * Persist the layout as a {@code scene-layout} JSON component and record the ledger entry.
	 * Best-effort and a no-op when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload, String depthModel) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("scene-layout");
			// One row per asset in v1. Reserved for a frame number once video lands.
			request.setVariant("");
			// The layout is only as good as the depth that produced it, so that provenance belongs on the row.
			request.setProducerVersion(depthModel);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, depthModel, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist scene layout for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), depthModel, null);
		}
	}

	private static double round(double value) {
		return Math.round(value * 1_000_000d) / 1_000_000d;
	}

	/**
	 * A detection before depth has been sampled for it.
	 */
	private record RawDetection(String id, String label, String type, String source, BoxF box, double confidence) {
	}

}
