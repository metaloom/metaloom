package io.metaloom.cortex.common.node;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.common.metrics.NoopCortexMetrics;
import io.metaloom.cortex.common.node.reuse.NodeOutputReuse;
import io.metaloom.cortex.common.node.reuse.ReusePolicy;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputLookupResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Abstract base for media processing nodes. The input is always {@link LoomMedia}
 * and the output type {@code O} represents the computed value.
 *
 * <p><strong>Output reuse.</strong> Before {@link #compute} runs, the node asks Loom whether it
 * already emitted something for this asset given the same question - the same kind, options and
 * inputs, condensed into a reuse key ({@link NodeOutputReuse}). On a hit it replays the stored port
 * values and returns {@link ResultOrigin#REMOTE} without computing or persisting anything; on a miss
 * it computes and stores what it emitted for the next run. The lookup also returns the asset, so it
 * replaces the asset load this class always made - reuse costs no extra request. A node opts out or
 * narrows the scope with {@link #reusePolicy()}, and {@code recompute} - an option on every node, or
 * a key on the pipeline node definition - forces the computation and replaces the stored output.</p>
 *
 * @param <T> the options type for this node
 */
public abstract class AbstractMediaNode<T extends CortexNodeOptions> extends AbstractFilesystemNode<LoomMedia, T> {

	public static final Logger log = LoggerFactory.getLogger(AbstractMediaNode.class);

	/**
	 * Metrics catalog. Field-injected by Dagger for container-built nodes; defaults to a no-op so
	 * manually-constructed nodes (tests, offline helpers) never NPE. Subclasses use it to record
	 * upstream AI calls.
	 */
	@javax.inject.Inject
	protected CortexMetrics metrics = NoopCortexMetrics.INSTANCE;

	/** The pipeline node definition this instance runs under, or null outside a pipeline. Set per task by the registrar. */
	private JsonObject definition;

	/** {@code recompute} as set on the pipeline node definition - the only place a worker-configured node can be told per pipeline. */
	private boolean definitionRecompute;

	/** What the node last reported to the ledger as its producer version; stored with the output so the two agree. */
	private String producerVersion;

	/**
	 * The ledger state this execution recorded, or null. A node that computed but failed to write its typed result reports SUCCESS to the engine
	 * and FAILED to the ledger; storing its output would make the missing typed result permanent, because every later run would replay instead of
	 * writing.
	 */
	private ResultState recordedState;

	public AbstractMediaNode(LoomClient client, CortexOptions cortexOption, T option) {
		super(client, cortexOption, option);
	}

	/**
	 * Hand this instance the pipeline node definition it runs under. Called by the registrar for every task, before the first {@code process}; a node
	 * instance is never shared between tasks.
	 *
	 * @param nodeDef the flattened node definition, or null
	 */
	public void useDefinition(JsonObject nodeDef) {
		this.definition = nodeDef == null ? null : nodeDef.copy();
		Object flag = nodeDef == null ? null : nodeDef.getValue("recompute");
		this.definitionRecompute = Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
	}

	/**
	 * Whether this execution must compute even when Loom holds a stored output - set on the node's options or on its pipeline node definition.
	 */
	public boolean isRecompute() {
		return definitionRecompute || (options() != null && options().isRecompute());
	}

	/**
	 * How widely this node may replay a stored output. {@link ReusePolicy#SHARED} unless the node overrides it.
	 */
	protected ReusePolicy reusePolicy() {
		return ReusePolicy.SHARED;
	}

	/**
	 * Part of the reuse key. Bump it when the node's output changes for the same options and inputs - a fixed bug, a new algorithm - so stored
	 * outputs from the old code are no longer replayed. Options, inputs and the output ports' shape are in the key already.
	 */
	protected String reuseVersion() {
		return "1";
	}

	/**
	 * Port values that must not be replayed because they reflect Loom state rather than the node's own work - who a reviewer has since confirmed a
	 * face as, for example. Called on a hit; each entry replaces the stored value of that port.
	 *
	 * @param asset the asset, as the lookup returned it
	 * @return port id to the current value; empty by default
	 */
	protected Map<String, Object> refreshOnReuse(AssetResponse asset) {
		return Map.of();
	}

	@Override
	public NodeResult process(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return ctx.skipped("Disabled").next();
		}
		LoomMedia media = ctx.media();
		if (!media.exists()) {
			metrics.recordFileMissing();
			return ctx.failure("File " + media.path() + " not found").abort();
		}
		if (!isProcessable(ctx)) {
			return ctx.skipped("unprocessable").next();
		}
		try {
			recordedState = null;
			String reuseKey = reuseKey(ctx);
			AssetResponse asset;
			if (reuseKey != null && !isRecompute()) {
				NodeOutputLookupResponse lookup = lookup(media, reuseKey);
				asset = lookup != null ? lookup.getAsset() : fetchAsset(media);
				if (lookup != null && lookup.getOutput() != null) {
					NodeResult replayed = replay(ctx, asset, lookup.getOutput());
					if (replayed != null) {
						return replayed;
					}
				}
			} else {
				asset = fetchAsset(media);
			}
			NodeResult result = compute(ctx, asset);
			if (reuseKey != null) {
				storeOutput(ctx, asset, reuseKey, result);
			}
			return result;
		} catch (Exception e) {
			log.error("Failure to process media", e);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * The reuse key for this execution, or null when this execution does not take part in reuse - the node opted out, there is no Loom to ask, or the
	 * options cannot be hashed.
	 */
	String reuseKey(NodeContext<LoomMedia> ctx) {
		ReusePolicy policy = reusePolicy();
		if (policy == ReusePolicy.NEVER || isOfflineMode() || client() == null) {
			return null;
		}
		try {
			return NodeOutputReuse.reuseKey(name(), reuseVersion(), NodeOutputReuse.outputSignature(getClass()), options(),
				this instanceof PipelineConfigurable ? definition : null,
				policy == ReusePolicy.PER_NODE_ID ? nodeId() : null,
				ctx.inputPorts(), ctx.media().absolutePath());
		} catch (Exception e) {
			// Never a reason to fail the item: the node simply computes, as it always did.
			log.warn("[{}] cannot compute a reuse key, computing without reuse: {}", name(), e.getMessage());
			return null;
		}
	}

	/**
	 * Ask Loom for the asset and the stored output in one request. Null when the request failed for any reason, including an asset Loom does not
	 * know - the caller then falls back to the plain asset load.
	 */
	private NodeOutputLookupResponse lookup(LoomMedia media, String reuseKey) {
		try {
			return client().lookupNodeOutput(media.getSHA512(), name(), reuseKey).sync().body();
		} catch (Exception e) {
			log.debug("[{}] output lookup failed, computing: {}", name(), e.getMessage());
			return null;
		}
	}

	/**
	 * Replay a stored output, or return null to compute after all - when the stored form is unusable here, for instance a produced file that does not
	 * exist on this worker.
	 */
	private NodeResult replay(NodeContext<LoomMedia> ctx, AssetResponse asset, NodeOutputResponse stored) {
		String mediaPath = ctx.media().absolutePath();
		Map<String, PortOutput> outputs = NodeOutputReuse.decode(stored.getOutputs(), mediaPath);
		if (outputs == null) {
			log.debug("[{}] stored output {} is not usable on this worker, computing", name(), stored.getUuid());
			return null;
		}
		refreshOnReuse(asset).forEach((portId, value) -> {
			PortOutput replayedPort = outputs.get(portId);
			if (replayedPort != null) {
				outputs.put(portId, new PortOutput(replayedPort.port(), java.util.List.of(value)));
			}
		});
		ResultState state = ResultState.SKIPPED.name().equals(stored.getState()) ? ResultState.SKIPPED : ResultState.SUCCESS;
		// The ledger stays truthful per node instance - "has this node run on this asset" - and is the only durable trace that a run reused rather
		// than computed: the engine does not persist a task's origin.
		ctx.origin(ResultOrigin.REMOTE);
		recordNodeResult(asset, ctx, state, stored.getReason(), stored.getProducerVersion(), null);
		print(ctx, "REUSED", "stored output from " + (stored.getEdited() == null ? "an earlier run" : stored.getEdited()));
		return new NodeResult(null, state, ctx.duration(), stored.getReason(), outputs, null, ResultOrigin.REMOTE);
	}

	/**
	 * Store what the node just computed, for the next run. Only a successful computation is stored: a replay is already stored, a skip may have a
	 * transient reason, and a failure must never become a permanent answer. Best-effort - a failed store costs the next run a computation, nothing
	 * else.
	 */
	private void storeOutput(NodeContext<LoomMedia> ctx, AssetResponse asset, String reuseKey, NodeResult result) {
		if (asset == null || client() == null || result == null || result.getState() != ResultState.SUCCESS) {
			return;
		}
		if (result.getOrigin() != null && result.getOrigin() != ResultOrigin.COMPUTED) {
			return;
		}
		if (recordedState == ResultState.FAILED) {
			log.debug("[{}] the typed result for asset {} was not persisted; not storing the output for reuse", name(), asset.getUuid());
			return;
		}
		try {
			JsonObject outputs = NodeOutputReuse.encode(result.getOutputs(), ctx.media().absolutePath());
			if (outputs == null) {
				log.debug("[{}] output for asset {} is too large to store for reuse", name(), asset.getUuid());
				return;
			}
			NodeOutputCreateRequest request = new NodeOutputCreateRequest()
				.setNodeKind(name())
				.setReuseKey(reuseKey)
				.setNodeId(nodeId())
				.setProducerVersion(producerVersion)
				.setState(result.getState().name())
				.setOutputs(outputs)
				.setRunUuid(ctx.runUuid() == null ? null : ctx.runUuid().toString());
			client().createAssetNodeOutput(asset.getUuid(), request).sync();
		} catch (Exception e) {
			log.warn("[{}] failed to store output for reuse on asset {}: {}", name(), asset.getUuid(), e.getMessage());
		}
	}

	/**
	 * Fetch the asset from Loom, or null if in offline mode.
	 */
	private AssetResponse fetchAsset(LoomMedia media) {
		if (isOfflineMode()) {
			return null;
		}
		try {
			SHA512 sha512 = media.getSHA512();
			return client().loadAsset(sha512).sync().body();
		} catch (Exception e) {
			log.debug("Failed to fetch asset from Loom: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Check whether the media in the context is processable by this node.
	 */
	protected abstract boolean isProcessable(NodeContext<LoomMedia> ctx);

	/**
	 * Compute the output for the given media. Store the result via
	 * {@code ctx.output(key, value)} so it appears in the {@link NodeResult}
	 * and is persisted by the pipeline's cache backends.
	 *
	 * @param ctx   the processing context
	 * @param asset optional asset response from Loom (may be used to skip computation)
	 * @return the result containing the computed output
	 */
	protected abstract NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception;

	/**
	 * The graph-local node id this instance runs under, or {@code ""} outside a pipeline DAG.
	 *
	 * <p>Overridden by nodes whose configuration is per pipeline instance
	 * ({@link PipelineConfigurable}). {@code asset_node_result} is
	 * {@code UNIQUE (asset_uuid, node_kind, node_id)}, so two instances of one kind in a single
	 * graph - say an {@code s3-sink} writing thumbnails to one bucket and another writing archives
	 * to a second - would otherwise collide on the empty id and overwrite each other's ledger row.</p>
	 *
	 * <p>The default is deliberately {@code ""}: every node that reads its options from the worker
	 * configuration can only ever have one meaningful instance per kind, and changing their ledger
	 * key would orphan the rows they have already written.</p>
	 *
	 * @return the node id, never null
	 */
	protected String nodeId() {
		return "";
	}

	/**
	 * Record an entry in the per-asset processing ledger ({@code asset_node_result}).
	 *
	 * <p>
	 * This is the second half of the persistence template: after a node writes its typed payload (transcript, detection, json component, ...) it calls
	 * this to leave a queryable "this node ran on this asset, with this outcome" marker. Best-effort — a ledger failure is logged but never fails the
	 * node — and a no-op when there is no asset or no Loom client (offline mode), so every node can call it unconditionally.
	 * </p>
	 *
	 * @param asset           the asset processed, or null (then this is a no-op)
	 * @param ctx             the node context (supplies the run duration)
	 * @param state           SUCCESS, SKIPPED or FAILED
	 * @param reason          failure detail, or null on success
	 * @param producerVersion the model/algorithm version, or null
	 * @param resultRef       advisory pointer to the written payload ({@link #resultRef(String, UUID...)}), or null when nothing was written
	 */
	protected void recordNodeResult(AssetResponse asset, NodeContext<LoomMedia> ctx, ResultState state, String reason, String producerVersion,
		JsonObject resultRef) {
		if (producerVersion != null) {
			this.producerVersion = producerVersion;
		}
		this.recordedState = state;
		if (asset == null || client() == null) {
			return;
		}
		try {
			NodeResultCreateRequest ledger = new NodeResultCreateRequest();
			ledger.setNodeKind(name());
			ledger.setNodeId(nodeId());
			ledger.setProducerVersion(producerVersion);
			ledger.setState(state.name());
			// The origin the node actually determined for this item - LOCAL for a cache replay,
			// REMOTE for a value fetched from Loom. COMPUTED only as the fallback for a node that
			// recorded nothing; hardcoding it here used to make a replay indistinguishable from
			// real work in the ledger.
			ResultOrigin origin = ctx.resultOrigin();
			ledger.setOrigin((origin != null ? origin : ResultOrigin.COMPUTED).name());
			ledger.setReason(reason);
			ledger.setDurationMs(ctx.duration());
			if (resultRef != null) {
				ledger.setResultRef(resultRef);
			}
			// Null outside a pipeline run - the columns are ON DELETE SET NULL, so the ledger row
			// outlives the run it references.
			if (ctx.runUuid() != null) {
				ledger.setRunUuid(ctx.runUuid().toString());
			}
			if (ctx.taskUuid() != null) {
				ledger.setTaskUuid(ctx.taskUuid().toString());
			}
			client().createAssetNodeResult(asset.getUuid(), ledger).sync();
		} catch (Exception e) {
			log.warn("Failed to record node result for asset {}: {}", asset.getUuid(), e.getMessage());
		}
	}

	/**
	 * Build the advisory {@code result_ref} pointer {@code {"table": <table>, "uuids": [...]}} recorded on a ledger entry.
	 *
	 * @param table the payload table the node wrote to, e.g. {@code asset_transcript_comp}
	 * @param uuids the rows written
	 * @return the pointer, or null when no rows were written
	 */
	protected JsonObject resultRef(String table, UUID... uuids) {
		if (uuids == null || uuids.length == 0) {
			return null;
		}
		JsonArray arr = new JsonArray();
		for (UUID uuid : uuids) {
			if (uuid != null) {
				arr.add(uuid.toString());
			}
		}
		return new JsonObject().put("table", table).put("uuids", arr);
	}
}
