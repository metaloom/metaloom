package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompListResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;

public interface FingerprintCompMethods {

	/**
	 * Persist (upsert) a perceptual fingerprint component for an asset. Keyed by {@code (asset, node_kind, algorithm, window_index)}; re-posting the
	 * same key rewrites the row in {@code asset_fingerprint_comp}.
	 */
	LoomClientRequest<FingerprintCompResponse> createAssetFingerprintComp(UUID assetUuid, FingerprintCompCreateRequest request);

	LoomClientRequest<FingerprintCompResponse> loadAssetFingerprintComp(UUID assetUuid, UUID compUuid);

	LoomClientRequest<FingerprintCompListResponse> listAssetFingerprintComps(UUID assetUuid);

	LoomClientRequest<NoResponse> deleteAssetFingerprintComp(UUID assetUuid, UUID compUuid);

}
