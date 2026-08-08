package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import io.metaloom.cortex.node.filter.FilterStrategy.Classification;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routes each item down one branch per configured bucket.
 *
 * <p>
 * <strong>The output port is the branch.</strong> The node emits exactly one of its bucket ports per
 * item — or {@code other} — and the engine skips any node wired to a port that carried nothing.
 * There is no {@code PASS}/{@code REJECT} edge attribute involved and no limit of two branches: an
 * author who wants five languages configures five buckets and gets five ports.
 * </p>
 *
 * <p>
 * This replaced eight {@code filter-*} kinds that were advertised in the palette and could never
 * run. They extended {@code AbstractPipelineNode} rather than {@code FilesystemNode}, so they could
 * not be bound into the executable-kind map at all — a graph using one saved, validated, dispatched
 * and then failed at the worker.
 * </p>
 *
 * <p>
 * The buckets live in the pipeline definition, so this node is a {@link PipelineConfigurable} and is
 * reconfigured per task. <strong>It must therefore never be a Dagger {@code @Singleton}</strong> —
 * {@code FilterNodeSingletonTest} guards that.
 * </p>
 */
@NodeSpec(nodeId = "filter", name = "Filter",
	description = "Route each item down one branch per configured bucket. The output port is the branch: a node wired to a port that "
		+ "carried nothing for an item is skipped for that item.",
	// call_split rather than filter_alt: this routes N ways, it does not merely gate.
	icon = "call_split", category = NodeCategory.FILTER, dynamicPorts = true, defaultConcurrency = 4,
	// The filter contract has only ever carried 'enabled' of the three common knobs.
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true) })
public class FilterNode extends AbstractMediaNode<FilterNodeOptions> implements PipelineConfigurable {

	private static final Logger log = LoggerFactory.getLogger(FilterNode.class);

	public static final String KIND = "filter";

	private static final int RESULT_CACHE_SIZE = 50_000;

	@PortDoc(label = "Media", description = "The item being routed")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/**
	 * Declared optional <em>on the descriptor</em> — a runtime port carries only its id, content type
	 * and cardinality, and {@code required}/{@code selective} live on the {@code PortSpec} because
	 * only the editor and the engine act on them. Leaving this unwired is a usable configuration:
	 * every item lands in {@code other}, which is far easier to diagnose than a node that refuses to
	 * start.
	 */
	@PortDoc(label = "Text", required = false,
		description = "The text to classify - a transcript, extracted document text or a caption. "
			+ "Without it every item lands in 'other'")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	/**
	 * The catch-all branch, emitted when nothing else matched.
	 *
	 * <p>
	 * Selectivity is a descriptor property, not a runtime one: {@code PortGraphAnalyzer} reads it off
	 * the {@code PortSpec} when the graph is parsed, and the node's job is simply to write one bucket
	 * port and stay silent on the others.
	 * </p>
	 */
	@PortDoc(hidden = true)
	public static final OutputPort<String> OUT_OTHER = OutputPort.one(Classification.OTHER, ContentTypeRegistry.MEDIA_ANY, String.class);

	/**
	 * The boolean verdict, so a graph still using the older {@code PASS}/{@code REJECT} edges keeps
	 * routing. {@code NodeTaskResult.getFilterPassed()} finds it by its {@code control/} content
	 * type, not by name.
	 */
	@PortDoc(hidden = true)
	public static final OutputPort<Boolean> OUT_PASSED = OutputPort.one("passed", ContentTypeRegistry.CONTROL_FILTER, Boolean.class);

	/** Where the item landed, for a node that wants the decision rather than the item. Never selective. */
	@PortDoc(hidden = true)
	public static final OutputPort<String> OUT_BUCKET = OutputPort.one("bucket", ContentTypeRegistry.SCALAR_STRING, String.class);

	/**
	 * The verdict per media path. Keyed with a digest of the configuration as well, because the same
	 * worker may run two filter nodes over the same asset and they must not share an answer.
	 */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final Map<FilterBy, Provider<FilterStrategy>> strategies;

	private String nodeId = KIND;

	private String configHash = "";

	private boolean configured;

	@Inject
	public FilterNode(@Nullable LoomClient client, CortexOptions cortexOption, FilterNodeOptions options,
		Map<FilterBy, Provider<FilterStrategy>> strategies) {
		super(client, cortexOption, options);
		this.strategies = strategies;
	}

	@Override
	public String name() {
		return KIND;
	}

	/**
	 * The port carrying the items that landed in one bucket.
	 *
	 * <p>
	 * The id must stay the bucket id verbatim: {@code FilterPortResolver} derives the descriptor's
	 * ports from the same {@code buckets} option and produces exactly these names. Anything else and
	 * the author would wire an edge to a port the node never writes.
	 * </p>
	 */
	public static OutputPort<String> bucketPort(String bucketId) {
		return OutputPort.one(bucketId, ContentTypeRegistry.MEDIA_ANY, String.class);
	}

	@Override
	public void configure(JsonObject nodeDef) {
		FilterNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("filterBy")) {
			String raw = nodeDef.getString("filterBy");
			try {
				options.setFilterBy(FilterBy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Filter node '" + nodeId + "' has unknown filterBy '" + raw
					+ "'; expected one of " + java.util.Arrays.toString(FilterBy.values()));
			}
		}
		if (nodeDef.containsKey("tagSource")) {
			String raw = nodeDef.getString("tagSource");
			try {
				options.setTagSource(TagSource.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Filter node '" + nodeId + "' has unknown tagSource '" + raw
					+ "'; expected one of " + java.util.Arrays.toString(TagSource.values()));
			}
		}
		if (nodeDef.containsKey("buckets")) {
			options.setBuckets(nodeDef.getJsonArray("buckets", new JsonArray()));
		}
		if (nodeDef.containsKey("model")) {
			options.setModel(nodeDef.getString("model"));
		}
		if (nodeDef.containsKey("openaiUrl")) {
			options.setOpenaiUrl(nodeDef.getString("openaiUrl"));
		}
		if (nodeDef.containsKey("maxTextChars")) {
			options.setMaxTextChars(nodeDef.getInteger("maxTextChars"));
		}
		if (nodeDef.containsKey("minConfidence")) {
			options.setMinConfidence(nodeDef.getDouble("minConfidence"));
		}

		// Failing here surfaces as an immediate task failure naming the node, which beats a node
		// that quietly routes every item to 'other' for the whole run.
		var validation = options.validate();
		if (!validation.isValid()) {
			throw new IllegalStateException("Filter node '" + nodeId + "' is misconfigured: " + String.join("; ", validation.getErrors()));
		}

		// The generic validation above cannot know that '<10 megabytes' is not a size threshold - only
		// the strategy does. Asking the one strategy we resolved keeps this ignorant of which exist, and
		// makes a typo'd hint an immediate failure rather than a run in which everything lands in 'other'.
		FilterStrategy strategy = strategy();
		List<String> bucketErrors = strategy.validateBuckets(options.buckets());
		if (!bucketErrors.isEmpty()) {
			throw new IllegalStateException("Filter node '" + nodeId + "' is misconfigured: " + String.join("; ", bucketErrors));
		}

		// Every input that can change the answer belongs in here. Two nodes differing only in an
		// omitted one would share a result-cache entry and a producerVersion, so the second would
		// re-emit the first's verdict.
		configHash = hash(options.getFilterBy() + " " + options.getBuckets().encode() + " " + options.getModel()
			+ " " + options.getTagSource() + " " + strategy.version());
		configured = true;
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		// An unconfigured filter has no buckets, so it could only ever answer 'other' - and it would
		// do so for every item in the run. Routing nothing is better than routing everything wrongly.
		return configured;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		String cacheKey = ctx.media().absolutePath() + "|" + configHash;

		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			emit(ctx, cached);
			return ctx.origin(LOCAL).next();
		}

		List<FilterBucket> buckets = options().buckets();
		FilterStrategy strategy = strategy();
		FilterItem item = item(ctx, asset, strategy);

		Classification classification;
		try {
			classification = strategy.classify(item, options(), buckets);
		} catch (Exception e) {
			// The worker could not do the job it was given - an unreachable model is not a routing
			// decision. .abort() and not .next(): NodeContextImpl.next() ignores the failure cause
			// and would report SUCCESS.
			log.error("Filter node {} failed to classify {}: {}", nodeId, ctx.media().absolutePath(), e.getMessage(), e);
			persistFailure(ctx, asset, e.getMessage());
			return ctx.failure("Failed to classify " + ctx.media().absolutePath() + ": " + e.getMessage()).abort();
		}

		emit(ctx, classification.bucketId());
		resultCache.put(cacheKey, classification.bucketId());
		persist(ctx, asset, classification);
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Write the one branch this item took, plus the two ports that fire for every item.
	 *
	 * <p>
	 * Exactly one bucket port is written and the rest are left alone. That silence <em>is</em> the
	 * routing: the engine skips every consumer wired to a port that carried nothing.
	 * </p>
	 */
	private void emit(NodeContext<LoomMedia> ctx, String bucketId) {
		String path = ctx.media().absolutePath();
		if (Classification.OTHER.equals(bucketId)) {
			ctx.output(OUT_OTHER, path);
		} else {
			ctx.output(bucketPort(bucketId), path);
		}
		ctx.output(OUT_PASSED, !Classification.OTHER.equals(bucketId));
		ctx.output(OUT_BUCKET, bucketId);
	}

	/**
	 * Gather what the strategy is allowed to see about one item.
	 *
	 * <p>
	 * The asset is whatever {@code AbstractMediaNode} already loaded, so tags cost nothing. Only a
	 * strategy that asked for reactions pays the extra round trip, and a failed fetch is recorded as
	 * "unavailable" rather than thrown: an unreachable Loom is not a reason to abort a task over
	 * thousands of files, and "we could not find out" is a materially different answer from "there
	 * is no rating" — the strategy decides what to do with each.
	 * </p>
	 */
	private FilterItem item(NodeContext<LoomMedia> ctx, AssetResponse asset, FilterStrategy strategy) {
		FilterItem item = FilterItem.of(ctx, asset, ctx.optionalInput(IN_TEXT).orElse(null));
		if (!strategy.needsReactions() || asset == null || client() == null) {
			return item;
		}
		try {
			return item.withReactions(client().listAssetReaction(asset.getUuid()).sync().body().getData());
		} catch (Exception e) {
			log.debug("Filter node {} could not read the reactions of asset {}: {}", nodeId, asset.getUuid(), e.getMessage());
			return item.withoutReactions();
		}
	}

	private FilterStrategy strategy() {
		Provider<FilterStrategy> provider = strategies.get(options().getFilterBy());
		if (provider == null) {
			throw new IllegalStateException("No filter strategy is registered for " + options().getFilterBy()
				+ "; known strategies: " + strategies.keySet());
		}
		return provider.get();
	}

	/**
	 * Persist the routing decision as a {@code filter} JSON component (variant = node id) and record
	 * a ledger entry. Best-effort, and a clean no-op when the asset is unknown to Loom or we run
	 * offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, Classification classification) {
		if (asset == null || client() == null) {
			return;
		}
		JsonObject data = new JsonObject()
			.put("filterBy", options().getFilterBy().name())
			.put("bucket", classification.bucketId())
			.put("confidence", classification.confidence())
			.mergeIn(classification.detail());
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(KIND);
			// Variant is the pipeline node id so two filter nodes can both write for one asset.
			request.setVariant(nodeId);
			request.setProducerVersion(producerVersion());
			request.setData(data);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist filter result for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
		}
	}

	private void persistFailure(NodeContext<LoomMedia> ctx, AssetResponse asset, String reason) {
		if (asset == null || client() == null) {
			return;
		}
		recordNodeResult(asset, ctx, ResultState.FAILED, reason, producerVersion(), null);
	}

	/**
	 * Scoped per node id so two filter instances do not collide on the ledger's
	 * {@code UNIQUE (asset_uuid, node_kind, node_id)}.
	 */
	@Override
	protected String nodeId() {
		return KIND + ":" + nodeId;
	}

	/**
	 * Changes whenever the meaning of this node's answer changes — the thing filtered on, the bucket
	 * set, the model, or the prompt.
	 */
	String producerVersion() {
		return KIND + "/1:" + options().getFilterBy() + ":" + configHash;
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
}
