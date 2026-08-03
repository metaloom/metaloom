package io.metaloom.cortex.node.script;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;

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
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.node.script.engine.CompiledScript;
import io.metaloom.cortex.node.script.engine.ScriptBindings;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.ScriptException;
import io.metaloom.cortex.node.script.engine.ScriptLimits;
import io.metaloom.cortex.node.script.engine.ScriptLogger;
import io.metaloom.cortex.node.script.engine.ScriptOutputCollector;
import io.metaloom.cortex.node.script.engine.ScriptOutputException;
import io.metaloom.cortex.node.script.engine.ScriptSignal;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentEntry;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Runs a user-supplied script as a pipeline node.
 *
 * <p>
 * Every other node encodes one fixed capability. This one encodes none: it reads the media handle
 * and any upstream outputs, runs a script the pipeline author wrote, and emits whatever that
 * script declares. It exists so that a piece of glue - map a transcript to chapter marks,
 * threshold a metric into a tag, reshape an LLM answer - does not require a new Maven module and
 * a Cortex release.
 * </p>
 *
 * <h3>Configuration is per node instance</h3>
 * <p>
 * The script lives in the pipeline definition, so this node implements {@link PipelineConfigurable}
 * and is reconfigured per task. <strong>It must therefore never be a Dagger {@code @Singleton}</strong> -
 * see {@link PipelineConfigurable} for why. {@code ScriptNodeSingletonTest} guards this.
 * </p>
 *
 * <h3>Outputs</h3>
 * <p>
 * Outputs are declared in configuration and filled at runtime (see {@link ScriptValueType}). A
 * value may be multi-valued - a list of timeframes, several texts, several images - which is what
 * lets one media item produce a whole timeline without the pipeline engine needing to multiply
 * items.
 * </p>
 *
 * <h3>Persistence</h3>
 * <p>
 * Scalar, list and JSON outputs land in one {@code asset_json_comp} row with
 * {@code variant = nodeId}, so several script nodes coexist on one asset. {@code TIMEFRAMES}
 * outputs become real {@code asset_segment_comp} rows, one set per output key. Images stay in the
 * local {@code script_bin} cache - there is no byte-ingest endpoint for produced media - and only
 * the ledger records them.
 * </p>
 */
@NodeSpec(nodeId = "script", name = "Script", icon = "code", category = NodeCategory.TRANSFORM,
	description = "Run a small script over the media item and its upstream outputs, and emit any "
		+ "number of declared outputs - texts, numbers, JSON, timeframes or images.",
	// The outputs are declared per instance in the 'outputs' parameter; ScriptPortResolver derives
	// the handles from it, so there is no static output port here at all.
	dynamicPorts = true,
	// timeoutMs is hidden on AbstractNodeOptions because almost no contract advertises it. A script
	// node's wall-clock budget is a first-class authoring knob, so it is re-documented here.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "Wall-clock budget per media item", min = "1", order = 110))
public class ScriptNode extends AbstractMediaNode<ScriptNodeOptions> implements PipelineConfigurable {

	public static final Logger log = LoggerFactory.getLogger(ScriptNode.class);

	public static final String KIND = "script";

	/** The media item, ambient rather than transported - declared so the graph is fully wired. */
	@PortDoc(label = "Media", required = false,
		description = "The media item the script runs over. Leave unwired for a script that only reshapes upstream data")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/**
	 * The structured payload the script reads as its {@code data} binding.
	 *
	 * <p>
	 * This is what replaced the {@code requiredInputs} option - a list of {@code nodeId:outputKey}
	 * strings that all had to be present or the node skipped. Since the port is optional and the
	 * script decides what to do with an empty {@code data}, "skip when the input is missing" is
	 * now expressed by the graph and by the script's own {@code ctx.skip()}.
	 * </p>
	 */
	@PortDoc(label = "Data", required = false,
		description = "A structured payload from an upstream node, handed to the script as its input data")
	public static final InputPort<String> IN_DATA = InputPort.one("data", ContentTypeRegistry.STRUCT_JSON, String.class);

	/**
	 * Text from an upstream node - a transcript, extracted document content, a caption.
	 *
	 * <p>
	 * Separate from {@link #IN_DATA} because text is not a struct: deriving a reading time, a tag or
	 * a chapter list from upstream prose is the most common thing a script is asked to do, and
	 * routing it through the JSON port would mean every such script started by unwrapping a document
	 * it never wanted.
	 * </p>
	 */
	@PortDoc(label = "Text", required = false,
		description = "Text from an upstream node, such as a transcript or extracted document content")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);
	/** Schema type of the JSON component this node writes. */
	public static final String SCHEMA_TYPE = "script";

	/** Sub-directory of the meta path where produced images land. */
	public static final String BIN_DIR = "script_bin";

	/**
	 * In-heap skip cache of the encoded output bag.
	 *
	 * <p>
	 * ⚠️ Keyed by media path <em>and script hash</em>, unlike every other node's cache. Keying by
	 * path alone would mean that editing a script re-emits the previous script's results for the
	 * worker's lifetime, with no way to invalidate short of a restart.
	 * </p>
	 */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final Map<String, Provider<ScriptEngine>> engines;
	private final CortexOptions cortexOptions;

	/** Node id from the pipeline definition; the persisted component's variant and the log prefix. */
	private String nodeId = KIND;

	private List<ScriptOutputSpec> outputSpecs = List.of();
	private ScriptEngine engine;
	private CompiledScript compiled;
	private String scriptHash = "";

	@Inject
	public ScriptNode(@Nullable LoomClient client, CortexOptions cortexOptions, ScriptNodeOptions options,
		Map<String, Provider<ScriptEngine>> engines) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.engines = engines;
	}

	@Override
	public String name() {
		return KIND;
	}

	/**
	 * Apply the pipeline node definition and compile the script.
	 *
	 * <p>
	 * Compiling here rather than in {@code compute} matters: a run processes many media items with
	 * the same script, and per-item compilation would dominate the node's cost. It also means a
	 * syntax error fails the task immediately with a clear message instead of failing identically
	 * for every file.
	 * </p>
	 */
	@Override
	public void configure(JsonObject nodeDef) {
		ScriptNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("engine")) {
			options.setEngine(nodeDef.getString("engine"));
		}
		if (nodeDef.containsKey("script")) {
			options.setScript(nodeDef.getString("script"));
		}
		if (nodeDef.containsKey("outputs")) {
			options.setOutputs(nodeDef.getJsonArray("outputs", new JsonArray()));
		}
		if (nodeDef.containsKey("params")) {
			options.setParams(nodeDef.getJsonObject("params", new JsonObject()));
		}
		if (nodeDef.containsKey("trusted")) {
			options.setTrusted(nodeDef.getBoolean("trusted"));
		}
		if (nodeDef.containsKey("allowNetwork")) {
			options.setAllowNetwork(nodeDef.getBoolean("allowNetwork"));
		}
		if (nodeDef.containsKey("allowFilesystem")) {
			options.setAllowFilesystem(nodeDef.getBoolean("allowFilesystem"));
		}
		// `timeoutMs` is also a structural pipeline-node field. Reading the same key is deliberate:
		// an author setting a node timeout means the script's budget, and PipelineNode.timeoutMs()
		// is not enforced by anything, so this node is where it has to take effect.
		if (nodeDef.containsKey("timeoutMs")) {
			options.setTimeoutMs(nodeDef.getLong("timeoutMs"));
		}
		if (nodeDef.containsKey("statementLimit")) {
			options.setStatementLimit(nodeDef.getLong("statementLimit"));
		}
		if (nodeDef.containsKey("maxOutputBytes")) {
			options.setMaxOutputBytes(nodeDef.getInteger("maxOutputBytes"));
		}
		if (nodeDef.containsKey("maxLogLines")) {
			options.setMaxLogLines(nodeDef.getInteger("maxLogLines"));
		}

		var validation = options.validate();
		if (validation.isInvalid()) {
			throw new IllegalStateException(String.join("; ", validation.getErrors()));
		}

		outputSpecs = ScriptOutputSpec.parse(options.getOutputs());
		engine = resolveEngine(options.getEngine());
		scriptHash = hash(options.getScript());

		closeCompiled();
		try {
			compiled = engine.compile(options.getScript(), limits());
		} catch (ScriptException e) {
			throw new IllegalStateException("script did not compile: " + e.getMessage(), e);
		}
	}

	private ScriptEngine resolveEngine(String id) {
		Provider<ScriptEngine> provider = engines.get(id);
		if (provider == null) {
			throw new IllegalStateException("unknown script engine '" + id + "'; available engines are " + engines.keySet());
		}
		return provider.get();
	}

	private ScriptLimits limits() {
		ScriptNodeOptions options = options();
		return new ScriptLimits(options.isTrusted(), options.isAllowNetwork(), options.isAllowFilesystem(),
			options.getTimeoutMs(), options.getStatementLimit(), options.getMaxLogLines());
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		if (compiled == null) {
			// Not configured from a pipeline definition. Running an unconfigured script node would
			// be a no-op for every item; skipping says so instead of silently succeeding.
			return false;
		}
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		String cacheKey = media.absolutePath() + "|" + scriptHash;

		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		ScriptOutputCollector collector = new ScriptOutputCollector(outputSpecs, binarySink(media));
		ScriptLogger logger = new ScriptLogger(log, nodeId, options().getMaxLogLines());
		ScriptBindings bindings = new ScriptBindings(media, data(ctx), options().getParams().getMap(),
			collector, logger, limits());

		try {
			compiled.execute(bindings);
		} catch (ScriptSignal signal) {
			if (signal.isFailure()) {
				recordNodeResult(asset, ctx, ResultState.FAILED, signal.getMessage(), producerVersion(), null);
				return ctx.failure(signal.getMessage()).abort();
			}
			return ctx.skipped(signal.getMessage()).next();
		} catch (ScriptOutputException e) {
			// A declaration mismatch is an authoring error, so it must be loud rather than dropped.
			log.warn("[{}] script produced an invalid output for {}: {}", nodeId, media.absolutePath(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			return ctx.failure(e.getMessage()).abort();
		} catch (ScriptException e) {
			log.warn("[{}] script failed for {}: {}", nodeId, media.absolutePath(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			return ctx.failure(e.getMessage()).abort();
		}

		Map<String, Object> values = collector.values();
		JsonObject payload = toJsonPayload(values);
		String encoded = payload.encode();
		if (encoded.length() > options().getMaxOutputBytes()) {
			String reason = "script output of " + encoded.length() + " bytes exceeds maxOutputBytes of " + options().getMaxOutputBytes();
			recordNodeResult(asset, ctx, ResultState.FAILED, reason, producerVersion(), null);
			return ctx.failure(reason).abort();
		}

		emitValues(ctx, values);
		resultCache.put(cacheKey, new JsonObject(new LinkedHashMap<>(values)).encode());

		ctx.print("DONE", values.size() + " output(s)");
		persist(ctx, asset, values, payload);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Sink for produced images: {@code metaPath/script_bin/<nodeId>/<segment>/<key>-<n>.png}.
	 *
	 * <p>
	 * Mirrors {@code ThumbnailNode} / {@code ImageGenNode}. The bytes stay on the worker because
	 * there is no byte-ingest endpoint for produced media; downstream consumers therefore only see
	 * a path that is meaningful on this worker, which is why such graphs want an affinity group.
	 * </p>
	 */
	private ScriptOutputCollector.BinarySink binarySink(LoomMedia media) {
		return (outputKey, index, bytes) -> {
			SHA512 hash = media.getSHA512();
			Path base = cortexOptions.getMetaPath().resolve(BIN_DIR).resolve(nodeId);
			Path dir = hash == null ? base : HashUtils.segmentPath(base, hash);
			Path target = dir.resolve(outputKey + "-" + index + ".png");
			Files.createDirectories(target.getParent());
			Files.write(target, bytes);
			return target.toString();
		};
	}

	/**
	 * What the script sees as its {@code data} binding: the wired {@code struct/json} payload,
	 * decoded, or an empty map when nothing is connected.
	 */
	private Map<String, Object> data(NodeContext<LoomMedia> ctx) {
		Map<String, Object> data = new LinkedHashMap<>();
		String encoded = ctx.input(IN_DATA);
		if (encoded != null) {
			data.putAll(new JsonObject(encoded).getMap());
		}
		// Wired text appears as data.text so a script reads one binding regardless of which port it
		// arrived on. It is added after the struct payload rather than before, so a script that
		// genuinely wants a "text" field of its own JSON input is not overwritten by an unwired port.
		String text = ctx.input(IN_TEXT);
		if (text != null) {
			data.putIfAbsent("text", text);
		}
		return data;
	}

	/** Re-emit a cached bag. Values were stored encoded, so lists and objects come back as Json types. */
	private void emit(NodeContext<LoomMedia> ctx, JsonObject cached) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (String key : cached.fieldNames()) {
			values.put(key, cached.getValue(key));
		}
		emitValues(ctx, values);
	}

	/**
	 * Write the collected values to their declared ports.
	 *
	 * <p>
	 * A {@code MANY} declaration becomes one element per list entry rather than a single list
	 * value. That is the whole point of the cardinality: a downstream node with a {@code ONE}
	 * input then runs once per element instead of receiving an opaque list it has to unpack.
	 * </p>
	 */
	private void emitValues(NodeContext<LoomMedia> ctx, Map<String, Object> values) {
		for (ScriptOutputSpec spec : outputSpecs) {
			Object value = values.get(spec.key());
			if (value == null) {
				continue;
			}
			OutputPort<Object> port = spec.port();
			if (spec.type().isList()) {
				for (Object element : asList(value)) {
					ctx.outputElement(port, encodable(element));
				}
			} else {
				ctx.output(port, encodable(value));
			}
		}
	}

	private static List<?> asList(Object value) {
		if (value instanceof List<?> list) {
			return list;
		}
		if (value instanceof JsonArray array) {
			// A cache hit re-reads the bag through JsonObject, so a list comes back as a JsonArray.
			return array.getList();
		}
		return List.of(value);
	}

	/**
	 * Vert.x JSON types are not what the boundary coercer accepts, so they are encoded here rather
	 * than being rejected as "expected a JSON object, got JsonObject".
	 */
	private static Object encodable(Object value) {
		if (value instanceof JsonObject json) {
			return json.encode();
		}
		if (value instanceof JsonArray array) {
			return array.encode();
		}
		if (value instanceof List<?> list) {
			return new JsonArray(new ArrayList<>(list)).encode();
		}
		return value;
	}

	/**
	 * The subset of outputs that belongs in the JSON component: everything except images (whose
	 * bytes are local) and timeframes (which get real segment rows).
	 */
	private JsonObject toJsonPayload(Map<String, Object> values) {
		JsonObject payload = new JsonObject();
		values.forEach((key, value) -> {
			ScriptOutputSpec spec = specOf(key);
			if (spec != null && spec.type().isJsonPayload()) {
				payload.put(key, value);
			}
		});
		return payload;
	}

	private ScriptOutputSpec specOf(String key) {
		for (ScriptOutputSpec spec : outputSpecs) {
			if (spec.key().equals(key)) {
				return spec;
			}
		}
		return null;
	}

	/**
	 * Persist the run: the JSON component, one segment set per {@code TIMEFRAMES} output, and the
	 * ledger. Best-effort and a no-op offline or before the asset is known to Loom.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, Map<String, Object> values, JsonObject payload) {
		if (asset == null || client() == null) {
			return;
		}
		List<UUID> written = new ArrayList<>();
		String table = null;
		try {
			if (!payload.isEmpty()) {
				JsonCompCreateRequest request = new JsonCompCreateRequest();
				request.setNodeKind(name());
				request.setSchemaType(SCHEMA_TYPE);
				// One row per script node on the asset: the natural key includes the variant.
				request.setVariant(nodeId);
				request.setProducerVersion(producerVersion());
				request.setData(payload);
				written.add(client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid());
				table = "asset_json_comp";
			}
			for (Map.Entry<String, Object> entry : values.entrySet()) {
				ScriptOutputSpec spec = specOf(entry.getKey());
				if (spec == null || spec.type() != ScriptValueType.TIMEFRAMES) {
					continue;
				}
				persistSegments(asset, spec, entry.getValue());
				table = written.isEmpty() ? "asset_segment_comp" : table;
			}
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(),
				written.isEmpty() ? null : resultRef(table, written.toArray(UUID[]::new)));
		} catch (Exception e) {
			log.warn("[{}] failed to persist script results for asset {}: {}", nodeId, asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
		}
	}

	/**
	 * Write one {@code TIMEFRAMES} output as a segment set.
	 *
	 * <p>
	 * The endpoint replaces the whole set for {@code (asset, node_kind, segment_type)}, which is
	 * what a re-run wants: a script that now finds fewer marks should not leave the surplus behind.
	 * </p>
	 *
	 * <p>
	 * Two details are forced by the schema rather than chosen. {@code segment_type} is CHECK-
	 * constrained to {@code SCENE/SILENCE/SHOT/CHAPTER}, so it comes from the output's declared
	 * {@code segmentType} and cannot be the output key. And because the replace set is scoped by
	 * {@code node_kind}, every script node would otherwise share one scope and overwrite its
	 * neighbours' segments - so the segment rows are written under {@code script:<nodeId>}. The
	 * ledger still records the plain {@code script} kind.
	 * </p>
	 */
	private void persistSegments(AssetResponse asset, ScriptOutputSpec spec, Object value) throws Exception {
		if (!(value instanceof List<?> frames)) {
			return;
		}
		SegmentCompCreateRequest request = new SegmentCompCreateRequest();
		request.setNodeKind(segmentNodeKind());
		request.setSegmentType(spec.segmentType());
		request.setProducerVersion(producerVersion() + ";unit=ms");
		List<SegmentEntry> entries = new ArrayList<>();
		int seq = 0;
		for (Object raw : frames) {
			if (!(raw instanceof JsonObject frame)) {
				continue;
			}
			// SegmentEntry's bounds are unit-agnostic (SceneDetectionNode stores frame indices in
			// them). A script declares its frames in milliseconds, so that is what is stored, and
			// the producer version records the unit for consumers.
			entries.add(new SegmentEntry()
				.setSeq(seq++)
				.setTimeFrom(frame.getLong("startMs"))
				.setTimeTo(frame.getLong("endMs"))
				.setTitle(frame.getString("label")));
		}
		request.setSegments(entries);
		client().createAssetSegmentComps(asset.getUuid(), request).sync();
	}

	/**
	 * Producer version: engine plus a short script digest.
	 *
	 * <p>
	 * This gives the ledger something no other node records - <em>which</em> script produced a
	 * result. A changed script is visibly a different producer, which is the per-node versioning
	 * the rest of the node system still lacks.
	 * </p>
	 */
	/**
	 * The {@code node_kind} segment rows are written under.
	 *
	 * <p>
	 * Scoped per node id because the segment replace-set key is
	 * {@code (asset, node_kind, segment_type)}: with a plain {@code script} kind, a second script
	 * node writing CHAPTER marks would delete the first node's.
	 * </p>
	 */
	public String segmentNodeKind() {
		return KIND + ":" + nodeId;
	}

	String producerVersion() {
		return (engine == null ? options().getEngine() : engine.id()) + ":" + scriptHash;
	}

	/** Short digest of the script source; also the cache-key discriminator. */
	private static String hash(String script) {
		if (script == null) {
			return "";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the platform; if it is missing the JVM is broken.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private void closeCompiled() {
		if (compiled != null) {
			try {
				compiled.close();
			} catch (Exception e) {
				log.debug("[{}] failed to close previous compiled script: {}", nodeId, e.getMessage());
			}
			compiled = null;
		}
	}

	/** The outputs this node declares; exposed for tests and for the pipeline editor's connectors. */
	public List<ScriptOutputSpec> outputSpecs() {
		return outputSpecs;
	}

	@Override
	public void flush() {
		closeCompiled();
	}
}
