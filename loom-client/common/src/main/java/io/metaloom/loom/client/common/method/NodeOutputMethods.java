package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputListResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputLookupResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Node output reuse: what a node emitted for an asset, keyed by a hash of its options and inputs, so a later run with the same settings can skip the
 * work.
 */
public interface NodeOutputMethods {

	/**
	 * Load the asset together with the stored output for one node kind and reuse key. The output is null when the node has to compute. Answers 404 when
	 * the asset is unknown.
	 */
	LoomClientRequest<NodeOutputLookupResponse> lookupNodeOutput(AssetId id, String nodeKind, String reuseKey);

	default LoomClientRequest<NodeOutputLookupResponse> lookupNodeOutput(UUID assetUuid, String nodeKind, String reuseKey) {
		return lookupNodeOutput(assetId(assetUuid), nodeKind, reuseKey);
	}

	default LoomClientRequest<NodeOutputLookupResponse> lookupNodeOutput(SHA512 hash, String nodeKind, String reuseKey) {
		return lookupNodeOutput(assetId(hash), nodeKind, reuseKey);
	}

	/**
	 * Store what a node emitted. Keyed by {@code (asset, node kind, reuse key)}; storing the same key again replaces the output.
	 */
	LoomClientRequest<NodeOutputResponse> createAssetNodeOutput(UUID assetUuid, NodeOutputCreateRequest request);

	/**
	 * @param nodeKind restrict to one kind, or null for all
	 */
	LoomClientRequest<NodeOutputListResponse> listAssetNodeOutputs(UUID assetUuid, String nodeKind);

	/**
	 * Forget stored outputs so those nodes compute again. The typed results they wrote are untouched.
	 *
	 * @param nodeKind restrict to one kind, or null for all
	 */
	LoomClientRequest<NoResponse> deleteAssetNodeOutputs(UUID assetUuid, String nodeKind);

}
