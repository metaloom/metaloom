package io.metaloom.cortex.node.metadata;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

/**
 * Reads the metadata a file already carries - EXIF, IPTC, XMP, PDF Info, OOXML properties, ID3 -
 * normalises it onto a Dublin-Core-shaped vocabulary, and persists it.
 *
 * <p>
 * Every media file arrives with authored metadata: who shot it, where, under which licence, with
 * what title and keywords. Until this node existed MetaLoom parsed that information and printed it
 * to stdout. This is the node that keeps it.
 * </p>
 *
 * <h2>What it writes</h2>
 * <ul>
 * <li><b>{@code asset_json_comp}</b>, {@code schemaType = metadata}: the canonical envelope
 * ({@link AssetMetadata}). One row per asset per node kind, replaced in place on a re-run. Its
 * authored fields are indexed into {@code search_document}, which is what makes an ingested photo
 * caption findable.</li>
 * <li><b>{@code asset_geo_comp}</b>, one row per position reading, keyed by
 * {@code (asset, node_kind, method, time_from)}. {@code method} is the <em>source</em> - {@code exif},
 * {@code xmp}, {@code sidecar} - never the file format.</li>
 * <li><b>{@code asset_node_result}</b>: the ledger row, always.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 * <ul>
 * <li><b>Body text.</b> That is the {@code tika} node and its {@code content} port. This node reads
 * the metadata, not the document.</li>
 * <li><b>Measuring the media.</b> Real fps, frame count and bitrate come from the decoder, in the
 * {@code quality} node. This node reports only what the file <em>claims</em> about itself, and
 * containers do lie - rotation flags, truncated durations, VBR files quoting a nominal bitrate. The
 * two never overwrite each other.</li>
 * <li><b>Reverse geocoding.</b> A coordinate is not a place name. This node fills
 * {@code geo_alias} only when the file itself named the location (IPTC {@code City} /
 * {@code Country}).</li>
 * </ul>
 *
 * <h2>Privacy</h2>
 *
 * <p>
 * This node ingests PII by design: an EXIF GPS tag is frequently a home address and
 * {@code dc:creator} is a named person. {@code gpsPolicy} is therefore a first-class option and
 * lives on the pipeline, so a public-library pipeline can round coordinates while the internal
 * archive keeps them exact. {@code includeRaw} defaults to off because maker notes have carried
 * serial numbers and owner names.
 * </p>
 */
@NodeSpec(nodeId = "metadata", name = "Asset Metadata", icon = "info", category = NodeCategory.ANALYSIS,
	description = "Read the metadata already inside a file - EXIF, IPTC, XMP, PDF and Office properties, ID3 - "
		+ "and normalise it onto Dublin Core: title, creator, keywords, date, rights and GPS position.",
	defaultConcurrency = 4)
public class MetadataNode extends AbstractMediaNode<MetadataNodeOptions> implements PipelineConfigurable {

	public static final Logger log = LoggerFactory.getLogger(MetadataNode.class);

	public static final String KIND = "metadata";

	/** The component discriminator. Never change it once shipped: {@code search_extract_json_text} is a SQL CASE with no default branch, so a renamed schema type is silently skipped rather than reported. */
	public static final String SCHEMA_TYPE = "metadata";

	/** Bumped when the <em>meaning</em> of the envelope changes, which is what makes "recompute everything older than this" expressible. */
	public static final String PRODUCER_VERSION = "metadata/1";

	private static final int RESULT_CACHE_SIZE = 10_000;

	@PortDoc(label = "Media", description = "The file to read metadata from")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/** The canonical envelope, serialised. */
	@PortDoc(label = "Metadata",
		description = "The normalised metadata envelope: Dublin Core, rights, capture settings and position")
	public static final OutputPort<String> OUT_METADATA = OutputPort.one("metadata", ContentTypeRegistry.STRUCT_JSON, String.class);

	/**
	 * Title, description, keywords and creator, newline-joined - the authored prose and nothing else.
	 * This is what makes the node compose: it feeds {@code translate}, {@code sentiment}, {@code llm}
	 * and every other {@code text/*} consumer without any of them knowing what EXIF is.
	 */
	@PortDoc(label = "Text",
		description = "Title, description, keywords and creator, newline-joined - ready for any text consumer")
	public static final OutputPort<String> OUT_TEXT = OutputPort.one("text", ContentTypeRegistry.TEXT_PLAIN, String.class);

	/**
	 * {@code {lat, lon, altitudeM, accuracyM}}, written only when a coordinate was actually found.
	 * Nothing consumes it yet; it is the seam a {@code geocode} node plugs into.
	 *
	 * <p>
	 * Declared {@code one} rather than optional because that is how the port model expresses "may
	 * not be written": {@code OutputPort} has no optional cardinality, and an unwritten port simply
	 * delivers nothing downstream - the same shape {@code watermark} uses for its image/video branch.
	 * </p>
	 */
	@PortDoc(label = "Position", description = "Latitude and longitude, emitted only when the file carried a coordinate")
	public static final OutputPort<String> OUT_GEO = OutputPort.one("geo", ContentTypeRegistry.STRUCT_JSON, String.class);

	/**
	 * In-heap skip cache of the serialised envelope. Keyed by media path <b>and</b> the options
	 * digest: {@code gpsPolicy} and {@code includeRaw} change the output, so two differently
	 * configured instances of this node in one graph would otherwise serve each other's answers.
	 * Non-durable - the durable copy lives in Loom.
	 */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	/** Graph-local id. Also the ledger {@code node_id}, which is what lets two differently configured instances coexist on one asset. */
	private String nodeId = KIND;

	@Inject
	public MetadataNode(@Nullable LoomClient client, CortexOptions cortexOptions, MetadataNodeOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	protected String nodeId() {
		return nodeId;
	}

	@Override
	public void configure(JsonObject nodeDef) {
		MetadataNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("includeRaw")) {
			options.setIncludeRaw(nodeDef.getBoolean("includeRaw"));
		}
		if (nodeDef.containsKey("rawMaxKeys")) {
			options.setRawMaxKeys(nodeDef.getInteger("rawMaxKeys"));
		}
		if (nodeDef.containsKey("rawMaxValueBytes")) {
			options.setRawMaxValueBytes(nodeDef.getInteger("rawMaxValueBytes"));
		}
		if (nodeDef.containsKey("readXmpSidecar")) {
			options.setReadXmpSidecar(nodeDef.getBoolean("readXmpSidecar"));
		}
		if (nodeDef.containsKey("writeGeoComponent")) {
			options.setWriteGeoComponent(nodeDef.getBoolean("writeGeoComponent"));
		}
		if (nodeDef.containsKey("gpsTrackMaxSamples")) {
			options.setGpsTrackMaxSamples(nodeDef.getInteger("gpsTrackMaxSamples"));
		}
		if (nodeDef.containsKey("gpsPolicy")) {
			options.setGpsPolicy(GpsPolicy.parse(nodeDef.getString("gpsPolicy")));
		}
		if (nodeDef.containsKey("gpsRoundDecimals")) {
			options.setGpsRoundDecimals(nodeDef.getInteger("gpsRoundDecimals"));
		}
		if (nodeDef.containsKey("emitText")) {
			options.setEmitText(nodeDef.getBoolean("emitText"));
		}
		if (nodeDef.containsKey("licenseDetection")) {
			options.setLicenseDetection(nodeDef.getBoolean("licenseDetection"));
		}
		if (nodeDef.containsKey("dateFallback")) {
			options.setDateFallback(DateFallback.parse(nodeDef.getString("dateFallback")));
		}
		if (nodeDef.containsKey("excludeKeys")) {
			List<String> keys = new ArrayList<>();
			nodeDef.getJsonArray("excludeKeys").forEach(entry -> keys.add(String.valueOf(entry)));
			options.setExcludeKeys(keys);
		}

		var validation = options.validate();
		if (validation.isInvalid()) {
			// Failing here surfaces as a task failure naming the node, which beats a node that
			// silently ingests nothing for every item in the run.
			throw new IllegalStateException(String.join("; ", validation.getErrors()));
		}
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isImage() || media.isAudio() || media.isVideo() || media.isDocument();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String cacheKey = media.absolutePath() + ":" + options().digest();

		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			// A hit re-emits and skips re-persisting: the durable copy is already in Loom.
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		AssetMetadata metadata;
		try {
			RawMetadata raw = MetadataExtractor.extract(media, options());
			metadata = MetadataMapper.map(raw, options(), MetadataExtractor.modifiedIso(media));
		} catch (Exception e) {
			// A parser that fails is a failure. A file that carries nothing is not - that is a
			// successful run with an empty envelope, handled below.
			log.error("Failed to read metadata from {}", media.path(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), PRODUCER_VERSION, null);
			return ctx.failure("metadata extraction failed: " + e.getMessage()).abort();
		}

		JsonObject envelope = metadata.toJson();
		emit(ctx, envelope);
		resultCache.put(cacheKey, envelope.encode());

		ctx.print("DONE", describe(metadata));
		persist(ctx, asset, metadata, envelope);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Emit the ports from the envelope. Done from the serialised form so a cache hit and a fresh
	 * computation cannot drift apart.
	 */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject envelope) {
		ctx.output(OUT_METADATA, envelope.encode());
		if (options().isEmitText()) {
			ctx.output(OUT_TEXT, AssetMetadata.textFrom(envelope));
		}
		JsonObject geo = AssetMetadata.geoPortFrom(envelope);
		if (geo != null) {
			ctx.output(OUT_GEO, geo.encode());
		}
	}

	private static String describe(AssetMetadata metadata) {
		if (metadata.isEmpty()) {
			return "no embedded metadata";
		}
		String sources = String.join("+", metadata.getSources());
		return sources.isEmpty() ? "mapped" : sources;
	}

	/**
	 * Persist the envelope, the position readings and the ledger row. Best-effort throughout and a
	 * clean no-op offline or before the asset is known to Loom.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, AssetMetadata metadata, JsonObject envelope) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// One envelope per asset per node kind. The node id lives on the ledger row instead, so
			// re-running with different options replaces the envelope rather than accumulating one
			// per configuration.
			request.setVariant("");
			request.setProducerVersion(PRODUCER_VERSION);
			request.setData(envelope);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();

			writeGeoComponents(asset, metadata);

			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, PRODUCER_VERSION,
				resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("[{}] failed to persist metadata for asset {}: {}", nodeId, asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), PRODUCER_VERSION, null);
		}
	}

	/**
	 * One {@code asset_geo_comp} row per position reading.
	 *
	 * <p>
	 * The endpoint upserts on {@code (asset, node_kind, method, time_from)}, so a second run replaces
	 * the rows it wrote the first time instead of colliding with them.
	 * </p>
	 */
	private void writeGeoComponents(AssetResponse asset, AssetMetadata metadata) throws Exception {
		if (!options().isWriteGeoComponent()) {
			return;
		}
		String alias = metadata.placeLabel();
		for (GeoBlock geo : metadata.getGeo()) {
			AssetComponentCreateRequest request = new AssetComponentCreateRequest()
				.setType(AssetComponentType.GEO)
				.setSource(name())
				.setNodeId(nodeId)
				.setProducerVersion(PRODUCER_VERSION)
				.setMethod(geo.getMethod())
				.setTimeFrom(geo.getTimeFromMs())
				.setMeta(geo.toComponentMeta())
				.setGeo(new GeoLocationInfo()
					.setSource(name())
					.setLat(geo.getLat())
					.setLon(geo.getLon())
					// Only ever the name the file itself carried. Deriving one from the coordinate
					// is geocoding, and belongs to a node that can be pointed at a gazetteer.
					.setAlias(alias)
					.setAccuracyM(geo.getAccuracyM()));
			// Confidence is deliberately left null: a coordinate read out of a file is a recorded
			// value, not a probabilistic estimate, and scoring it 1.0 would be a lie of a different
			// kind.
			client().createAssetComponent(asset.getUuid(), request).sync();
		}
	}
}
