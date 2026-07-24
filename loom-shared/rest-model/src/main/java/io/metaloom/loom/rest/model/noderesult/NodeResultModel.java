package io.metaloom.loom.rest.model.noderesult;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

/**
 * Wire model for a per-asset processing ledger entry ({@code asset_node_result}).
 *
 * <p>
 * The ledger answers "has node X at version V processed asset A, and what happened" - independent of which payload table the result landed in. It is
 * the node-agnostic companion to the typed {@code asset_*_comp} rows and the reusable template every cortex node records after writing its payload.
 * </p>
 */
public interface NodeResultModel<T extends NodeResultModel<T>> extends MetaModel<T>, RestModel {

	/**
	 * Return the kind of node that produced the result, e.g. {@code whisper}. Part of the ledger identity {@code (asset, node_kind, node_id)}.
	 */
	String getNodeKind();

	T setNodeKind(String nodeKind);

	/**
	 * Return the graph-local node id, or the empty string when the node ran outside a pipeline. Part of the ledger identity.
	 */
	String getNodeId();

	T setNodeId(String nodeId);

	/**
	 * Return the model or algorithm version of the producer. {@code WHERE node_kind = ? AND producer_version <> ?} is the invalidation query.
	 */
	String getProducerVersion();

	T setProducerVersion(String producerVersion);

	/**
	 * Return the outcome: {@code SUCCESS}, {@code SKIPPED} or {@code FAILED}.
	 */
	String getState();

	T setState(String state);

	/**
	 * Return where the result came from: {@code COMPUTED}, {@code LOCAL} or {@code REMOTE}, or null when the producer did not report an origin.
	 */
	String getOrigin();

	T setOrigin(String origin);

	/**
	 * Return the skip reason or failure detail, or null.
	 */
	String getReason();

	T setReason(String reason);

	/**
	 * Return how long the node ran, in milliseconds, or null.
	 */
	Long getDurationMs();

	T setDurationMs(Long durationMs);

	/**
	 * Return an advisory pointer to the rows the node wrote, e.g. <code>{"table":"asset_transcript_comp","uuids":[...]}</code>. Advisory only - not a
	 * foreign key.
	 */
	JsonObject getResultRef();

	T setResultRef(JsonObject resultRef);

}
