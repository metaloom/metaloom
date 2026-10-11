package io.metaloom.loom.db.model.asset;

import java.time.Instant;
import java.util.UUID;

import io.vertx.core.json.JsonObject;

/**
 * What a node emitted for an asset when given one particular set of options and inputs (V2.116).
 *
 * <p>
 * The reuse store behind node output reuse: a node that finds a row for its {@link #reuseKey()} replays {@link #outputs()} instead of computing.
 * Not the ledger - {@link AssetNodeResult} is one row per node instance and records that a node ran; this is one row per configuration and records
 * what came out. A replay copy only: the typed component tables stay the domain record.
 * </p>
 *
 * @param uuid
 *            row identity
 * @param assetUuid
 *            the asset the node ran against
 * @param nodeKind
 *            the node kind, e.g. {@code whisper}
 * @param reuseKey
 *            the hash the node computed over its kind, reuse version, options and inputs; opaque to Loom
 * @param nodeId
 *            graph-local id of the node instance that last wrote the row; informational, not part of the key
 * @param producerVersion
 *            the model or algorithm version, or the empty string
 * @param state
 *            {@link AssetNodeResult#STATE_SUCCESS} or {@link AssetNodeResult#STATE_SKIPPED}; a failure is never stored
 * @param reason
 *            the skip reason, or null
 * @param outputs
 *            port id to {@code {contentType, cardinality, values}}, exactly as the values crossed the wire
 * @param runUuid
 *            the run that wrote the row, or null outside a run or once the run is pruned
 */
public record AssetNodeOutput(UUID uuid, UUID assetUuid, String nodeKind, String reuseKey, String nodeId, String producerVersion, String state,
	String reason, JsonObject outputs, UUID runUuid, Instant created, Instant edited) {

}
