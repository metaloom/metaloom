package io.metaloom.cortex.node.color;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.artifact.MediaArtifacts;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.color.ColorResult.ColorEntry;
import io.metaloom.cortex.node.color.LabKMeans.Cluster;
import io.metaloom.cortex.node.color.RegionResolver.Resolution;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Dominant-colour node. Clusters an image's pixels in CIELAB and reports the colours it finds as
 * HEX, RGB, HSL and CIELAB/LCh, plus a human-readable name in English and German.
 *
 * <p>
 * There is no model and no sidecar - all of it is arithmetic. The interesting parts are split out
 * and pure, so they are testable without an image: {@link ColorSpaces} and {@link ColorDistance}
 * for the colour science, {@link LabKMeans} for the clustering, {@link ColorNamer} for the naming
 * and {@link RegionResolver} for turning configuration plus upstream boxes into regions.
 * </p>
 *
 * <h2>What it measures</h2>
 *
 * Three region sources, merged rather than ranked, because they answer different questions:
 *
 * <ul>
 * <li>the <strong>whole frame</strong>, which is what "what colour is this photo" means;</li>
 * <li>a <strong>statically configured region</strong>, for a fixed crop such as a logo area;</li>
 * <li>every <strong>upstream detection box</strong>, so "what colour is this person wearing" can be
 * answered per face. The boxes are read by output key ({@code detections}) rather than by type, so
 * any detector that speaks that shape works - this node has no compile-time dependency on
 * facedetect.</li>
 * </ul>
 *
 * <h2>Reproducibility</h2>
 *
 * k-means is seeded deterministically and the pixels reach it in a fixed order, so the same image
 * and options always produce the same palette. That is a hard requirement, not a nicety: a colour
 * that changed between runs would make every downstream facet and every cached result unstable.
 *
 * <h2>Images only</h2>
 *
 * Video is deliberately out of scope. Per-frame colour needs a frame-extraction path and a
 * {@code variant} scheme to hold the results, which is separate work.
 */
@NodeSpec(nodeId = "dominant-color", name = "Dominant Colour", icon = "palette", category = NodeCategory.ANALYSIS,
	description = "Find the dominant colours of an image by clustering its pixels in CIELAB - for the whole "
		+ "frame, a configured region, and every upstream detection box. Reports each colour as HEX, RGB, HSL "
		+ "and CIELAB plus a readable name in English and German. No model and no GPU required.",
	defaultConcurrency = 4)
public class DominantColorNode extends AbstractMediaNode<DominantColorNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(DominantColorNode.class);

	@PortDoc(label = "Image", description = "The image whose pixels are clustered in CIELAB")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	/**
	 * The boxes to measure a colour for, from any detector.
	 *
	 * <p>
	 * This replaces the {@code detectionSources} option - a list of upstream node ids defaulting to
	 * {@code ["facedetect"]}, which produced no regions at all the moment the detector was named
	 * anything else. Declared {@code MANY} because one image has many boxes, and optional because
	 * measuring the whole frame is a perfectly good configuration on its own.
	 * </p>
	 */
	@PortDoc(label = "Detections", required = false,
		description = "Boxes to measure individually. Leave unwired to measure only the whole frame and any fixed region")
	public static final InputPort<String> IN_DETECTIONS = InputPort.many("detections", ContentTypeRegistry.DETECTION_ANY, String.class);

	@PortDoc(label = "Colour Result", description = "Per measured region: the ranked palette with HEX, RGB, HSL and CIELAB values")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_COLOR, String.class);

	@PortDoc(label = "Hex", description = "Dominant colour of the whole frame as #RRGGBB")
	public static final OutputPort<String> OUT_HEX = OutputPort.one("hex", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Colour Term",
		description = "Language-neutral name of the dominant colour, e.g. dark_blue - the stable key to match on")
	public static final OutputPort<String> OUT_TERM = OutputPort.one("term", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Name (English)", description = "Readable English name of the dominant colour")
	public static final OutputPort<String> OUT_NAME_EN = OutputPort.one("name_en", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Name (German)", description = "Readable German name of the dominant colour")
	public static final OutputPort<String> OUT_NAME_DE = OutputPort.one("name_de", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Region Count", description = "How many regions were measured, counting the whole frame and the fixed region")
	public static final OutputPort<Long> OUT_REGION_COUNT = OutputPort.one("region_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/** The JSON component schema type this node writes. */
	public static final String SCHEMA_TYPE = "dominant-color";

	/**
	 * Recorded as the {@code producerVersion} on every persisted row.
	 *
	 * <p>
	 * There is no model to name, but there is still a version worth recording: the term codebook in
	 * {@link ColorTerms} and the modifier table in {@link ColorNamer} decide every emitted name, and
	 * a retune of either changes results for already-processed assets. <strong>Bump this whenever
	 * one of those two changes</strong> - {@code DominantColorNodePersistenceTest} asserts the exact
	 * value, so a silent retune fails the build.
	 * </p>
	 */
	public static final String ALGORITHM_VERSION = "dominant-color/1";

	private static final int RESULT_CACHE_SIZE = 10_000;

	/** In-heap skip cache of the result JSON. Non-durable - the durable copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	@Inject
	public DominantColorNode(@Nullable LoomClient client, CortexOptions cortexOptions, DominantColorNodeOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "dominant-color";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		String cacheKey = cacheKey(ctx);

		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		BufferedImage image;
		try {
			// Shared with every other image node in this segment - quality reads the same
			// decode rather than opening the file again. Alone, this is still one ImageIO.read.
			image = MediaArtifacts.decodedImage(ctx);
		} catch (Exception e) {
			// A file that claims to be an image and cannot be decoded is a real problem, not a
			// normal outcome - unlike a photo with no faces in it.
			log.error("Failed to decode image {}", ctx.media().absolutePath(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), ALGORITHM_VERSION, null);
			// abort(), not next(): NodeContextImpl.next() ignores the failure cause and returns
			// SUCCESS, so failure(...).next() reports a green node for a broken file.
			return ctx.failure(e.getMessage()).abort();
		}

		try {
			int width = image.getWidth();
			int height = image.getHeight();
			Resolution resolution = new RegionResolver(options()).resolve(detections(ctx), width, height);
			if (resolution.regions().isEmpty()) {
				return ctx.skipped("no usable regions").next();
			}

			List<ColorResult> results = new ArrayList<>();
			int dropped = resolution.dropped();
			for (RegionSource region : resolution.regions()) {
				ColorResult result = measure(image, region);
				if (result == null) {
					dropped++;
					continue;
				}
				results.add(result);
			}
			if (results.isEmpty()) {
				// A fully transparent PNG, or every box filtered away by the alpha gate.
				return ctx.skipped("no usable pixels").next();
			}

			JsonObject payload = payload(width, height, results, dropped, resolution.truncated());
			emit(ctx, payload);
			resultCache.put(cacheKey, payload.encode());

			ColorEntry primary = results.get(0).dominant();
			ctx.print("DONE", primary.rgb().hex() + " " + primary.name().en() + " / " + primary.name().de()
				+ " over " + results.size() + " region(s)");

			persist(ctx, asset, payload);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to compute dominant colour for media {}", ctx.media().absolutePath(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), ALGORITHM_VERSION, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * @return the region's palette, or null when it holds too few usable pixels to be worth
	 *         clustering
	 */
	private ColorResult measure(BufferedImage image, RegionSource region) {
		int maxSamples = options().getMaxSamples();
		int alphaThreshold = options().getAlphaThreshold();

		double[] lab = PixelSampler.sampleLab(image, region.box(), maxSamples, alphaThreshold);
		int usable = lab.length / 3;
		// The geometric drop already happened in RegionResolver; this is the drop that can only be
		// decided after the alpha gate has run, e.g. a box over a transparent area.
		if (usable < options().getMinRegionPixels()) {
			return null;
		}

		// Cap k by the distinct colour count before seeding, so a two-tone image never asks for
		// five clusters and lets k-means++ pick duplicate centres.
		int distinct = PixelSampler.distinctColors(image, region.box(), maxSamples, alphaThreshold,
			options().getClusterCount());
		int k = Math.max(1, Math.min(options().getClusterCount(), distinct));

		LabKMeans.Result clustered = LabKMeans.cluster(lab, k, options().getMaxIterations(),
			options().getConvergenceEpsilon(), options().getSeed());
		if (clustered.isEmpty()) {
			return null;
		}
		if (!clustered.converged()) {
			log.info("k-means did not converge within {} iterations for region '{}'; emitting the current state",
				options().getMaxIterations(), region.id());
		}

		ColorNamer namer = new ColorNamer(options().getAchromaticChroma(), options().getBlackLightness(),
			options().getWhiteLightness());

		List<ColorEntry> palette = new ArrayList<>(clustered.clusters().size());
		for (Cluster cluster : clustered.clusters()) {
			Rgb rgb = ColorSpaces.labToRgb(cluster.center());
			palette.add(new ColorEntry(cluster.share(), rgb, cluster.center(), ColorSpaces.rgbToHsl(rgb),
				namer.name(cluster.center())));
			if (!options().isEmitPalette()) {
				break;
			}
		}
		return new ColorResult(region, clustered.usablePixels(), clustered.converged(), List.copyOf(palette));
	}

	private void emit(NodeContext<LoomMedia> ctx, JsonObject payload) {
		JsonArray regions = payload.getJsonArray("regions");
		ctx.output(OUT_RESULT, payload.encode());
		ctx.output(OUT_REGION_COUNT, (long) regions.size());

		// The scalars describe the first region, which is the whole frame whenever it is enabled.
		// They exist so a filter node or a search indexer can ask "is this photo mostly blue"
		// without parsing a JSON string.
		JsonObject dominant = regions.getJsonObject(0).getJsonObject("dominant");
		JsonObject name = dominant.getJsonObject("name");
		ctx.output(OUT_HEX, dominant.getString("hex"));
		ctx.output(OUT_TERM, name.getString("term"));
		ctx.output(OUT_NAME_EN, name.getString("en"));
		ctx.output(OUT_NAME_DE, name.getString("de"));
	}

	private JsonObject payload(int width, int height, List<ColorResult> results, int dropped, int truncated) {
		JsonArray regions = new JsonArray();
		for (ColorResult result : results) {
			regions.add(regionJson(result));
		}
		return new JsonObject()
			.put("image", new JsonObject().put("width", width).put("height", height))
			.put("sampling", new JsonObject()
				.put("maxSamples", options().getMaxSamples())
				.put("clusterCount", options().getClusterCount())
				.put("seed", options().getSeed())
				.put("alphaThreshold", options().getAlphaThreshold()))
			.put("regions", regions)
			.put("truncated", new JsonObject().put("regions", truncated).put("dropped", dropped));
	}

	private JsonObject regionJson(ColorResult result) {
		RegionSource region = result.region();
		JsonObject json = new JsonObject()
			.put("id", region.id())
			.put("source", region.source())
			.put("kind", region.kind().name())
			.put("bbox", new JsonObject()
				.put("x", region.box().x())
				.put("y", region.box().y())
				.put("w", region.box().w())
				.put("h", region.box().h()))
			.put("pixels", result.pixels())
			.put("converged", result.converged());

		if (region.label() != null) {
			json.put("label", region.label());
		}
		if (region.type() != null) {
			json.put("type", region.type());
		}
		if (region.frame() != null) {
			json.put("frame", region.frame());
		}
		if (region.confidence() != null) {
			json.put("confidence", round(region.confidence(), 4));
		}

		// dominant duplicates palette[0] rather than indexing it. Nine consumers out of ten want
		// only the dominant colour, and regions[0].dominant.hex reads better than
		// regions[0].palette[0].hex.
		json.put("dominant", entryJson(result.dominant()));

		JsonArray palette = new JsonArray();
		for (ColorEntry entry : result.palette()) {
			palette.add(entryJson(entry));
		}
		json.put("palette", palette);
		return json;
	}

	private static JsonObject entryJson(ColorEntry entry) {
		Rgb rgb = entry.rgb();
		Lab lab = entry.lab();
		Hsl hsl = entry.hsl();
		ColorName name = entry.name();
		Double hue = lab.hue();

		return new JsonObject()
			.put("share", round(entry.share(), 4))
			.put("hex", rgb.hex())
			.put("rgb", new JsonObject().put("r", rgb.r()).put("g", rgb.g()).put("b", rgb.b()))
			.put("hsl", new JsonObject()
				.put("h", round(hsl.h(), 1))
				.put("s", round(hsl.s(), 1))
				.put("l", round(hsl.l(), 1)))
			.put("lab", new JsonObject()
				.put("l", round(lab.l(), 2))
				.put("a", round(lab.a(), 2))
				.put("b", round(lab.b(), 2)))
			.put("lch", new JsonObject()
				.put("c", round(lab.chroma(), 2))
				.put("h", hue == null ? null : round(hue, 1)))
			.put("name", new JsonObject()
				.put("term", name.term())
				.put("lightness", name.lightness().name())
				.put("chroma", name.chroma().name())
				.put("en", name.en())
				.put("de", name.de())
				.put("distance", round(name.distance(), 2)));
	}

	/**
	 * Persist the palette as a {@code dominant-color} JSON component and record the ledger entry.
	 * Best-effort and a no-op when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// One row per asset - every region lives inside the payload. Reserved for a frame
			// number once video lands.
			request.setVariant("");
			request.setProducerVersion(ALGORITHM_VERSION);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, ALGORITHM_VERSION,
				resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist dominant colour for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), ALGORITHM_VERSION, null);
		}
	}

	/**
	 * The encoded detection elements wired into this node, in sequence order.
	 */
	private List<String> detections(NodeContext<LoomMedia> ctx) {
		return ctx.inputs(IN_DETECTIONS).stream().map(Element::value).toList();
	}

	/**
	 * The cache key covers the media path, every wired detection element and every option that
	 * changes the answer.
	 *
	 * <p>
	 * Deliberately not just the path. A node whose result depends on its inputs and caches on the
	 * path alone returns the first detector's answer when the same file is re-run behind a
	 * different one - a stale-result bug that never surfaces as an error.
	 * </p>
	 */
	private String cacheKey(NodeContext<LoomMedia> ctx) {
		List<String> payloads = options().isUseDetections() ? detections(ctx) : List.of();
		int optionsHash = Objects.hash(payloads,
			options().getClusterCount(), options().getMaxSamples(), options().getMaxIterations(),
			options().getConvergenceEpsilon(), options().getSeed(), options().getAlphaThreshold(),
			options().getMinRegionPixels(), options().getMaxRegions(),
			options().isIncludeWholeImage(), options().isUseDetections(), options().isEmitPalette(),
			options().getRegionX(), options().getRegionY(), options().getRegionW(), options().getRegionH(),
			options().getRegionCoordinates(), options().getAchromaticChroma(),
			options().getBlackLightness(), options().getWhiteLightness());
		return ctx.media().absolutePath() + "|" + Integer.toHexString(optionsHash);
	}

	private static double round(double value, int decimals) {
		double factor = Math.pow(10, decimals);
		return Math.round(value * factor) / factor;
	}
}
