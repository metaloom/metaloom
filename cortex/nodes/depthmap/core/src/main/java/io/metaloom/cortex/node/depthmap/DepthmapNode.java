package io.metaloom.cortex.node.depthmap;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

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
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Depth-map node. Runs monocular depth estimation over an image asset and writes the resulting depth map to a local cache.
 *
 * <p>
 * The inference runs in the FastAPI depth sidecar (see {@code sidecars/depth}); this node is a pure HTTP client via {@link DepthmapClient}.
 * </p>
 *
 * <h2>The NEARNESS convention</h2>
 *
 * <p>
 * The produced PNG is <strong>16-bit grayscale where 65535 is NEAREST to the camera</strong>, not a distance. Raw model output is not consistent
 * between families - Depth Anything and MiDaS predict disparity (bigger = nearer) while ZoeDepth predicts metres (bigger = farther) - so the sidecar
 * normalizes everything to that one convention before it reaches Java. Every consumer depends on it; inverting it produces relations that are exactly
 * backwards while the node still reports SUCCESS.
 * </p>
 *
 * <p>
 * Following the {@code ThumbnailNode}/{@code TtsNode}/{@code ImageGenNode} pattern, the PNG is written to a local cache under
 * {@code metaPath/depthmap_bin} and only the {@code asset_node_result} ledger entry is recorded in Loom - the bytes stay local (there is no
 * byte-ingest endpoint for produced media yet). Unlike those nodes this one <em>does</em> record a {@code producerVersion}: which depth model produced
 * a map materially changes its numbers.
 * </p>
 *
 * <p>
 * 🔴 Because the map is worker-local, any node consuming it (notably {@code scene-layout}) must run on the same worker. Pin both into one affinity
 * group in the pipeline definition.
 * </p>
 */
@NodeSpec(nodeId = "depthmap", name = "Depth Map", icon = "layers", category = NodeCategory.ANALYSIS,
	description = "Estimate a per-pixel depth map from a single image. The map is written to a local cache as a 16-bit PNG where the brightest "
		+ "pixels are nearest the camera.",
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor
	// advertises it. This node does, so it re-documents the inherited field here.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "HTTP request timeout", min = "1", order = 9000))
// The sidecar holds one model on one device, so parallel calls from a single worker only queue behind
// each other and inflate memory - hence the default concurrency of 1.
public class DepthmapNode extends AbstractMediaNode<DepthmapNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(DepthmapNode.class);

	@PortDoc(label = "Image", description = "The image to estimate per-pixel depth for")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	/** The metadata a consumer needs to interpret the map; {@code map} is the worker-local PNG the metadata points at. */
	@PortDoc(label = "Depth Metadata", description = "Mode, model, map dimensions and the value range needed to interpret the map")
	public static final OutputPort<String> OUT_META = OutputPort.one("meta", ContentTypeRegistry.STRUCT_DEPTHMAP, String.class);

	@PortDoc(label = "Depth Map", description = "The 16-bit PNG in the worker's local cache; the brightest pixels are nearest the camera")
	public static final OutputPort<String> OUT_MAP = OutputPort.one("map", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** The only convention this node accepts from the sidecar. A mismatch is a hard failure rather than a guess. */
	public static final String CONVENTION_NEARNESS = "NEARNESS";

	/** In-heap skip cache of the metadata JSON (which embeds the artifact path), keyed by media path, to avoid re-inferring within this worker's
	 * lifetime. The 16-bit PNG itself is a durable local artifact under {@code metaPath/depthmap_bin}. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final DepthmapClient depthmapClient;
	private final CortexOptions cortexOptions;

	@Inject
	public DepthmapNode(@Nullable LoomClient client, CortexOptions cortexOptions, DepthmapNodeOptions options, DepthmapClient depthmapClient) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.depthmapClient = depthmapClient;
	}

	@Override
	public String name() {
		return "depthmap";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		// Video would need per-keyframe depth and a storage shape for it; that is a documented follow-up.
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit a locally cached result instead of re-inferring. On a hit the ledger entry already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			JsonObject meta = new JsonObject(cached);
			// The metadata outlives the artifact only if someone cleared metaPath underneath us; fall through to recompute in that case.
			if (Files.exists(Path.of(meta.getString("path")))) {
				metrics.recordAiCacheHit("depthmap");
				emit(ctx, meta);
				return ctx.origin(LOCAL).next();
			}
		}

		String model = null;
		try {
			BufferedImage source = DepthImages.read(media.file());
			BufferedImage image = DepthImages.downscale(source, options().getMaxDim());

			long aiStart = System.currentTimeMillis();
			JsonObject result;
			try {
				result = depthmapClient.depth(image, options().getMode(), options().getModel(), options().getMaxDim());
			} catch (RuntimeException e) {
				metrics.recordAiCall("depthmap", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("depthmap", true, System.currentTimeMillis() - aiStart);
			model = result.getString("model");

			String convention = result.getString("convention");
			if (!CONVENTION_NEARNESS.equals(convention)) {
				// Refuse rather than guess: every downstream ordering depends on knowing which way "closer" runs.
				throw new IOException("Depth sidecar returned convention '" + convention + "', expected " + CONVENTION_NEARNESS);
			}

			byte[] png = Base64.getDecoder().decode(result.getString("png_b64"));
			Path mapPath = resolveDepthmapPath(media);
			Files.createDirectories(mapPath.getParent());
			Files.write(mapPath, png);

			JsonObject meta = buildMeta(result, source, mapPath);
			emit(ctx, meta);
			resultCache.put(path, meta.encode());

			ctx.print("DONE", meta.getInteger("width") + "x" + meta.getInteger("height") + " (" + png.length + " bytes)");

			// The map bytes live in the local depthmap_bin cache; record the ledger marker that this node produced them for the asset, stamped with the
			// model id. Uploading the bytes into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet (same as
			// ThumbnailNode/TtsNode/ImageGenNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, model, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to estimate depth for media {}", path, e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), model, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Emit the three node outputs from the metadata.
	 */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject meta) {
		ctx.output(OUT_FLAG, "DONE");
		ctx.output(OUT_MAP, meta.getString("path"));
		ctx.output(OUT_META, meta.encode());
	}

	/**
	 * Build the metadata a consumer needs to interpret the artifact: which model and convention produced it, how big the <em>map</em> is (which is not
	 * the source image size - the image was downscaled to {@code maxDim} first), how big the source image was, and where the file landed.
	 *
	 * <p>
	 * Carrying both dimension pairs is what lets a consumer project image-space bounding boxes into map space. Without them the projection is a guess.
	 * </p>
	 */
	private JsonObject buildMeta(JsonObject result, BufferedImage source, Path mapPath) {
		JsonObject meta = new JsonObject()
			.put("model", result.getString("model"))
			.put("convention", result.getString("convention"))
			.put("source", result.getString("source"))
			.put("width", result.getInteger("width"))
			.put("height", result.getInteger("height"))
			.put("imageWidth", source.getWidth())
			.put("imageHeight", source.getHeight())
			.put("path", mapPath.toString());
		if (result.getJsonObject("stats") != null) {
			meta.put("stats", result.getJsonObject("stats"));
		}
		if (result.getJsonObject("metric") != null) {
			meta.put("metric", result.getJsonObject("metric"));
		}
		return meta;
	}

	/**
	 * Resolve the local cache path for the produced map: {@code metaPath/depthmap_bin/<segment>/<sha512>.png}. Mirrors
	 * {@code ThumbnailNode}/{@code TtsNode}/{@code ImageGenNode}.
	 */
	private Path resolveDepthmapPath(LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".png";
		Path basePath = cortexOptions.getMetaPath().resolve("depthmap_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}
}
