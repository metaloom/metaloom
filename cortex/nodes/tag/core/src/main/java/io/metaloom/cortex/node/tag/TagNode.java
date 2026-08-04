package io.metaloom.cortex.node.tag;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.node.tag.TagNodeOptions.Normalize;
import io.metaloom.cortex.node.tag.TagStrategy.DesiredTag;
import io.metaloom.cortex.node.tag.TagStrategy.Outcome;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Attaches tags to an asset according to configured rules.
 *
 * <p>
 * The terminal for everything the palette already computes and cannot search. {@code sentiment},
 * {@code dominant-color}, {@code filter}, {@code quality} and {@code script} all persist their answers
 * as JSON components, which no query reaches; a tag lands in {@code tag_asset}, which a database
 * trigger folds into {@code search_document.tag_names} the moment the row is written. One edge turns
 * a computed value into a searchable facet.
 * </p>
 *
 * <p>
 * <strong>Tags are global.</strong> {@code (name, collection)} is unique across the instance, so this
 * node writes into a namespace it shares with every person using it. That is why {@code normalize},
 * {@code allowedTags} and {@code maxTags} are not optional extras, why {@code removeWithdrawn}
 * defaults to off, and why the node may only withdraw a tag it can prove it wrote — see
 * {@link #reconcile}.
 * </p>
 *
 * <p>
 * The rules live in the pipeline definition, so this is a {@link PipelineConfigurable} reconfigured
 * per task and therefore <strong>never a Dagger {@code @Singleton}</strong>; {@code TagNodeSingletonTest}
 * guards that.
 * </p>
 */
@NodeSpec(nodeId = "tag", name = "Tag",
	description = "Attach tags to an asset from declarative rules over wired inputs. Tags are searchable the moment they are written.",
	icon = "sell", category = NodeCategory.OUTPUT, defaultConcurrency = 4)
public class TagNode extends AbstractMediaNode<TagNodeOptions> implements PipelineConfigurable {

	private static final Logger log = LoggerFactory.getLogger(TagNode.class);

	public static final String KIND = "tag";

	/** The {@code schema_type} of the component recording what this node applied. */
	public static final String SCHEMA_TYPE = "tags";

	private static final int RESULT_CACHE_SIZE = 50_000;

	@PortDoc(label = "Media", description = "The item being tagged")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Text", required = false,
		description = "Prose to match against - a transcript, caption, OCR or document text")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	@PortDoc(label = "Number", required = false, description = "A single metric to threshold on, such as a sentiment score")
	public static final InputPort<Double> IN_NUMBER = InputPort.one("number", ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	@PortDoc(label = "Flag", required = false, description = "A boolean verdict from an upstream node")
	public static final InputPort<Boolean> IN_FLAG = InputPort.one("flag", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	/**
	 * A structured upstream result, addressed by JSON path.
	 *
	 * <p>
	 * Deliberately {@code ONE}. A {@code MANY} struct port whose rules picked their source by the
	 * element's origin node id would be the deleted {@code "nodeId:outputKey"} option wearing a
	 * disguise. A graph that must combine two structured sources uses two tag nodes, exactly as it
	 * would use two filter nodes.
	 * </p>
	 */
	@PortDoc(label = "Struct", required = false,
		description = "A structured upstream result - quality metrics, colours, metadata - addressed by dot path in a rule")
	public static final InputPort<String> IN_STRUCT = InputPort.one("struct", ContentTypeRegistry.STRUCT_ANY, String.class);

	/** Label lists. MANY, so several producers may feed it and the branch is gathered before the node runs. */
	@PortDoc(label = "Labels", required = false,
		description = "Strings to turn into tags - colour names, a polarity label, a filter bucket, a script output")
	public static final InputPort<String> IN_LABELS = InputPort.many("labels", ContentTypeRegistry.SCALAR_STRING, String.class);

	/**
	 * What this node did to this item.
	 *
	 * <p>
	 * 🔴 Not a {@code MANY} port of tag names, which is the obvious design and is rejected on the
	 * declaration: a node that runs {@code PER_ELEMENT} may not declare a {@code MANY} output, and one
	 * here would bar the node from ever sitting downstream of a fan-out such as {@code facedetect}.
	 * </p>
	 */
	@PortDoc(label = "Applied", description = "The applied, withdrawn and rejected tags for this item")
	public static final OutputPort<String> OUT_APPLIED = OutputPort.one("applied", ContentTypeRegistry.STRUCT_JSON, String.class);

	@PortDoc(label = "Count", description = "How many tags were attached; cheap to wire into a filter")
	public static final OutputPort<Long> OUT_COUNT = OutputPort.one("count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/**
	 * The verdict per media path, discriminated by a digest of the configuration: the same worker may
	 * run two tag nodes over one asset and they must not share an answer.
	 */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final Map<TagBy, Provider<TagStrategy>> strategies;

	private String nodeId = KIND;

	private String configHash = "";

	private boolean configured;

	@Inject
	public TagNode(@Nullable LoomClient client, CortexOptions cortexOptions, TagNodeOptions options,
		Map<TagBy, Provider<TagStrategy>> strategies) {
		super(client, cortexOptions, options);
		this.strategies = strategies;
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	public void configure(JsonObject nodeDef) {
		TagNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("tagBy")) {
			String raw = nodeDef.getString("tagBy");
			try {
				options.setTagBy(TagBy.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Tag node '" + nodeId + "' has unknown tagBy '" + raw
					+ "'; expected one of " + java.util.Arrays.toString(TagBy.values()));
			}
		}
		if (nodeDef.containsKey("normalize")) {
			String raw = nodeDef.getString("normalize");
			try {
				options.setNormalize(Normalize.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Tag node '" + nodeId + "' has unknown normalize '" + raw
					+ "'; expected one of " + java.util.Arrays.toString(Normalize.values()));
			}
		}
		if (nodeDef.containsKey("rules")) {
			options.setRules(nodeDef.getJsonArray("rules", new JsonArray()));
		}
		if (nodeDef.containsKey("collection")) {
			options.setCollection(nodeDef.getString("collection"));
		}
		if (nodeDef.containsKey("allowedTags")) {
			options.setAllowedTags(nodeDef.getJsonArray("allowedTags", new JsonArray()));
		}
		if (nodeDef.containsKey("maxTags")) {
			options.setMaxTags(nodeDef.getInteger("maxTags"));
		}
		if (nodeDef.containsKey("removeWithdrawn")) {
			options.setRemoveWithdrawn(nodeDef.getBoolean("removeWithdrawn"));
		}
		if (nodeDef.containsKey("dryRun")) {
			options.setDryRun(nodeDef.getBoolean("dryRun"));
		}
		if (nodeDef.containsKey("minConfidence")) {
			options.setMinConfidence(nodeDef.getDouble("minConfidence"));
		}

		// Failing here surfaces as a task failure naming the node, which beats a node that quietly
		// tags nothing - or worse, tags the wrong thing - for a whole run.
		var validation = options.validate();
		if (!validation.isValid()) {
			throw new IllegalStateException("Tag node '" + nodeId + "' is misconfigured: " + String.join("; ", validation.getErrors()));
		}

		configHash = hash(options.getTagBy() + " " + options.getRules().encode() + " " + options.getCollection()
			+ " " + options.getAllowedTags().encode() + " " + options.getNormalize() + " " + options.getMaxTags());
		configured = true;
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		// An unconfigured tag node has no rules, so it can only write nothing - for every item in the
		// run. Better to skip visibly than to look like it is working.
		return configured;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		String cacheKey = ctx.media().absolutePath() + "|" + configHash;

		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			// The durable copy already exists in Loom, so re-emit and skip both the evaluation and
			// every write. SUCCESS with a LOCAL origin, not SKIPPED.
			emit(ctx, new JsonObject(cached));
			return ctx.origin(LOCAL).next();
		}

		TagInputs inputs = TagInputs.of(ctx);
		Outcome outcome;
		try {
			outcome = strategy().compute(inputs, options());
		} catch (Exception e) {
			log.error("Tag node {} failed to decide tags for {}: {}", nodeId, ctx.media().absolutePath(), e.getMessage(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			// .abort() and not .next(): next() ignores the failure cause and would report SUCCESS.
			return ctx.failure("Failed to decide tags for " + ctx.media().absolutePath() + ": " + e.getMessage()).abort();
		}

		Verdict verdict = screen(outcome);

		JsonObject record;
		try {
			record = write(ctx, asset, verdict, outcome.skippedRules());
		} catch (Exception e) {
			log.error("Tag node {} failed to write tags for {}: {}", nodeId, ctx.media().absolutePath(), e.getMessage(), e);
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			return ctx.failure("Failed to write tags for " + ctx.media().absolutePath() + ": " + e.getMessage()).abort();
		}

		emit(ctx, record);
		resultCache.put(cacheKey, record.encode());
		ctx.print("DONE", record.getJsonArray("applied").size() + " tag(s)");
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Normalise, gate and cap what the strategy asked for.
	 *
	 * <p>
	 * Every guard lives here rather than in the strategies, so a later {@code LLM} or {@code VLM}
	 * strategy inherits the vocabulary control instead of having to remember it.
	 * </p>
	 */
	private Verdict screen(Outcome outcome) {
		Set<String> allowed = options().allowedTags();
		Map<String, AppliedTag> applied = new LinkedHashMap<>();
		List<JsonObject> rejected = new ArrayList<>();

		for (DesiredTag desired : outcome.tags()) {
			String name = options().normalize(desired.name());
			if (name == null) {
				rejected.add(reject(String.valueOf(desired.name()), "empty after normalisation"));
				continue;
			}
			if (desired.confidence() < options().getMinConfidence()) {
				rejected.add(reject(name, "confidence " + desired.confidence() + " below minConfidence"));
				continue;
			}
			if (!allowed.isEmpty() && !allowed.contains(name)) {
				rejected.add(reject(name, "not in allowedTags"));
				continue;
			}
			String collection = desired.collection() == null ? options().getCollection() : desired.collection();
			String key = key(name, collection);
			if (applied.containsKey(key)) {
				continue;
			}
			if (applied.size() >= options().getMaxTags()) {
				// A template rule over a gathered list has no natural bound, and every name that gets
				// through is a permanent row. Stop at the cap and say so.
				rejected.add(reject(name, "maxTags (" + options().getMaxTags() + ") reached"));
				continue;
			}
			applied.put(key, new AppliedTag(name, collection, desired.ruleId(), desired.confidence(), null));
		}
		return new Verdict(new ArrayList<>(applied.values()), rejected);
	}

	/**
	 * Attach what is new, withdraw what this node no longer stands behind, and record the verdict.
	 *
	 * @return the record written as the {@code tags} component, which is also the {@code applied} output
	 */
	private JsonObject write(NodeContext<LoomMedia> ctx, AssetResponse asset, Verdict verdict, List<String> skippedRules)
		throws LoomClientException {
		List<AppliedTag> withdrawn = List.of();

		if (asset != null && client() != null && !options().isDryRun()) {
			List<AppliedTag> previous = previouslyApplied(asset);
			for (AppliedTag tag : verdict.applied()) {
				tag.setUuid(attach(asset, tag));
			}
			withdrawn = reconcile(asset, previous, verdict.applied());
		}

		JsonObject record = record(verdict, withdrawn, skippedRules);

		if (asset != null && client() != null) {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// The variant is this pipeline node's id, so two tag nodes can both write for one asset -
			// and so each one reads back its own previous verdict rather than the other's.
			request.setVariant(nodeId);
			request.setProducerVersion(producerVersion());
			request.setData(record);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), resultRef("asset_json_comp", compUuid));
		}
		return record;
	}

	/** Attach one tag and return the uuid of the (possibly pre-existing, globally shared) tag row. */
	private UUID attach(AssetResponse asset, AppliedTag tag) throws LoomClientException {
		TagCreateRequest request = new TagCreateRequest();
		request.setName(tag.name());
		request.setCollection(tag.collection());
		return client().tagAsset(asset.getUuid(), request).sync().body().getUuid();
	}

	/**
	 * Withdraw the tags this node applied on an earlier run and no longer stands behind.
	 *
	 * <p>
	 * 🔴 <strong>The safety property of this node.</strong> A tag may be removed only when both hold:
	 * it is in <em>this node instance's</em> previous applied list, read back from the component it
	 * wrote, and its collection is one this instance writes into. A tag a person typed, or one another
	 * node instance applied, is never touched — {@code tag_asset} carries no provenance of its own, so
	 * that read-back is the only proof available, and a bug here silently destroys human curation.
	 * </p>
	 */
	private List<AppliedTag> reconcile(AssetResponse asset, List<AppliedTag> previous, List<AppliedTag> applied) {
		if (!options().isRemoveWithdrawn() || previous.isEmpty()) {
			return List.of();
		}
		Set<String> keep = new LinkedHashSet<>();
		for (AppliedTag tag : applied) {
			keep.add(key(tag.name(), tag.collection()));
		}
		Set<String> mine = writableCollections();

		List<AppliedTag> withdrawn = new ArrayList<>();
		for (AppliedTag tag : previous) {
			if (keep.contains(key(tag.name(), tag.collection())) || !mine.contains(tag.collection()) || tag.uuid() == null) {
				continue;
			}
			try {
				client().untagAsset(asset.getUuid(), tag.uuid()).sync();
				withdrawn.add(tag);
			} catch (Exception e) {
				// One tag that will not come off must not lose the tags that did go on.
				log.warn("Failed to withdraw tag {} from asset {}: {}", tag.name(), asset.getUuid(), e.getMessage());
			}
		}
		return withdrawn;
	}

	/** The collections this instance may write, and therefore the only ones it may withdraw from. */
	private Set<String> writableCollections() {
		Set<String> collections = new LinkedHashSet<>();
		collections.add(options().getCollection());
		for (TagRule rule : options().rules()) {
			if (rule.collection() != null) {
				collections.add(rule.collection());
			}
		}
		return collections;
	}

	/** This node instance's previous verdict for this asset, or an empty list when there is none. */
	private List<AppliedTag> previouslyApplied(AssetResponse asset) {
		List<AppliedTag> previous = new ArrayList<>();
		if (!options().isRemoveWithdrawn()) {
			// Nothing can be withdrawn, so nothing needs reading back. One less request per item.
			return previous;
		}
		try {
			List<JsonCompResponse> comps = client().listAssetJsonComps(asset.getUuid()).sync().body().getData();
			if (comps == null) {
				return previous;
			}
			for (JsonCompResponse comp : comps) {
				if (!SCHEMA_TYPE.equals(comp.getSchemaType()) || !nodeId.equals(comp.getVariant()) || comp.getData() == null) {
					continue;
				}
				JsonArray applied = comp.getData().getJsonArray("applied", new JsonArray());
				for (Object entry : applied) {
					if (entry instanceof JsonObject json) {
						previous.add(AppliedTag.from(json));
					}
				}
			}
		} catch (Exception e) {
			// Without the read-back nothing can be proven, so nothing is withdrawn. Failing closed is
			// the only safe direction when the alternative is deleting someone else's tags.
			log.warn("Failed to read back the previous tags for asset {}, not withdrawing anything: {}", asset.getUuid(), e.getMessage());
			return List.of();
		}
		return previous;
	}

	private JsonObject record(Verdict verdict, List<AppliedTag> withdrawn, List<String> skippedRules) {
		JsonArray applied = new JsonArray();
		verdict.applied().forEach(tag -> applied.add(tag.toJson()));
		JsonArray removed = new JsonArray();
		withdrawn.forEach(tag -> removed.add(tag.toJson()));
		JsonArray rejected = new JsonArray();
		verdict.rejected().forEach(rejected::add);
		JsonArray skipped = new JsonArray();
		skippedRules.forEach(skipped::add);

		return new JsonObject()
			.put("tagBy", options().getTagBy().name())
			.put("collection", options().getCollection())
			.put("dryRun", options().isDryRun())
			.put("applied", applied)
			.put("withdrawn", removed)
			.put("rejected", rejected)
			.put("skippedRules", skipped);
	}

	private void emit(NodeContext<LoomMedia> ctx, JsonObject record) {
		ctx.output(OUT_APPLIED, record.encode());
		ctx.output(OUT_COUNT, (long) record.getJsonArray("applied", new JsonArray()).size());
	}

	private TagStrategy strategy() {
		Provider<TagStrategy> provider = strategies.get(options().getTagBy());
		if (provider == null) {
			throw new IllegalStateException("No tag strategy is registered for " + options().getTagBy()
				+ "; known strategies: " + strategies.keySet());
		}
		return provider.get();
	}

	private static JsonObject reject(String name, String reason) {
		return new JsonObject().put("tag", name).put("reason", reason);
	}

	private static String key(String name, String collection) {
		return collection + "/" + name;
	}

	/**
	 * Scoped per node id so two tag instances do not collide on the ledger's
	 * {@code UNIQUE (asset_uuid, node_kind, node_id)}.
	 */
	@Override
	protected String nodeId() {
		return KIND + ":" + nodeId;
	}

	/** Changes whenever the meaning of this node's answer changes - the strategy or any rule. */
	String producerVersion() {
		return KIND + "/1:" + options().getTagBy() + ":" + configHash;
	}

	/** Short digest of the configuration; also the cache-key discriminator. */
	private static String hash(String config) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the platform; if it is missing the JVM is broken.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** What survived screening, and what did not. */
	private record Verdict(List<AppliedTag> applied, List<JsonObject> rejected) {
	}
}
