package io.metaloom.cortex.common.node;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.common.metrics.NoopCortexMetrics;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Abstract base for media processing nodes. The input is always {@link LoomMedia}
 * and the output type {@code O} represents the computed value.
 *
 * <p>The "already processed?" check is handled by the pipeline's persistent cache
 * (XAttr or sidecar file cache backend), not by the node itself. This node only
 * checks whether the media type is processable.</p>
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

	public AbstractMediaNode(LoomClient client, CortexOptions cortexOption, T option) {
		super(client, cortexOption, option);
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
			AssetResponse asset = fetchAsset(media);
			return compute(ctx, asset);
		} catch (Exception e) {
			log.error("Failure to process media", e);
			return ctx.failure(e.getMessage()).abort();
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
		if (asset == null || client() == null) {
			return;
		}
		try {
			NodeResultCreateRequest ledger = new NodeResultCreateRequest();
			ledger.setNodeKind(name());
			ledger.setNodeId(nodeId());
			ledger.setProducerVersion(producerVersion);
			ledger.setState(state.name());
			ledger.setOrigin(ResultOrigin.COMPUTED.name());
			ledger.setReason(reason);
			ledger.setDurationMs(ctx.duration());
			if (resultRef != null) {
				ledger.setResultRef(resultRef);
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
