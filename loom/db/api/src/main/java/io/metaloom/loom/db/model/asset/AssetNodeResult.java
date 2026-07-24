package io.metaloom.loom.db.model.asset;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

/**
 * Per-asset processing ledger entry: has node X at version V processed asset A, and what happened - regardless of which table the payload landed in.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, node_id)</code>.
 * </p>
 *
 * <p>
 * This is <b>not</b> the same as a pipeline node task. A task is per run item, is execution state, and is pruned with its run. A node result is per
 * asset, is catalog state, and outlives every run. Both may exist for the same execution; {@link #getRunUuid()} and {@link #getTaskUuid()} are the
 * join.
 * </p>
 */
public interface AssetNodeResult extends CUDElement<AssetNodeResult> {

	/** Mirrors ResultState in the Cortex node API. */
	String STATE_SUCCESS = "SUCCESS";

	/** Mirrors ResultState in the Cortex node API. */
	String STATE_SKIPPED = "SKIPPED";

	/** Mirrors ResultState in the Cortex node API. */
	String STATE_FAILED = "FAILED";

	/** Mirrors ResultOrigin in the Cortex node API. */
	String ORIGIN_COMPUTED = "COMPUTED";

	/** Mirrors ResultOrigin in the Cortex node API. */
	String ORIGIN_LOCAL = "LOCAL";

	/** Mirrors ResultOrigin in the Cortex node API. */
	String ORIGIN_REMOTE = "REMOTE";

	UUID getAssetUuid();

	AssetNodeResult setAssetUuid(UUID assetUuid);

	String getNodeKind();

	AssetNodeResult setNodeKind(String nodeKind);

	/**
	 * Return the graph-local node id; the empty string when the node ran outside a pipeline.
	 */
	String getNodeId();

	AssetNodeResult setNodeId(String nodeId);

	/**
	 * Return the model or algorithm version of the producer. <code>WHERE node_kind = ? AND producer_version &lt;&gt; ?</code> is the invalidation
	 * query.
	 */
	String getProducerVersion();

	AssetNodeResult setProducerVersion(String producerVersion);

	/**
	 * Return SUCCESS, SKIPPED or FAILED.
	 */
	String getState();

	AssetNodeResult setState(String state);

	/**
	 * Return COMPUTED, LOCAL or REMOTE, or null when the producer did not report an origin.
	 */
	String getOrigin();

	AssetNodeResult setOrigin(String origin);

	/**
	 * Return the skip reason or failure detail.
	 */
	String getReason();

	AssetNodeResult setReason(String reason);

	UUID getRunUuid();

	AssetNodeResult setRunUuid(UUID runUuid);

	UUID getTaskUuid();

	AssetNodeResult setTaskUuid(UUID taskUuid);

	Instant getStarted();

	AssetNodeResult setStarted(Instant started);

	Instant getFinished();

	AssetNodeResult setFinished(Instant finished);

	Long getDurationMs();

	AssetNodeResult setDurationMs(Long durationMs);

	/**
	 * Return an advisory pointer to the rows this node wrote, e.g.
	 * <code>{"table":"asset_transcript_comp","uuids":[...]}</code>. Not a foreign key: a node may write several tables, and those rows carry their own
	 * task reference. Do not build integrity on it.
	 */
	JsonObject getResultRef();

	AssetNodeResult setResultRef(JsonObject resultRef);
}
