package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

public interface JsonCompMethods {

	/**
	 * Persist (upsert) a generic JSON component result for an asset. Keyed by {@code (asset, node_kind, schema_type, variant)}; re-posting the same key
	 * rewrites the row in {@code asset_json_comp}.
	 */
	LoomClientRequest<JsonCompResponse> createAssetJsonComp(UUID assetUuid, JsonCompCreateRequest request);

	LoomClientRequest<JsonCompResponse> loadAssetJsonComp(UUID assetUuid, UUID compUuid);

	LoomClientRequest<JsonCompListResponse> listAssetJsonComps(UUID assetUuid);

	LoomClientRequest<NoResponse> deleteAssetJsonComp(UUID assetUuid, UUID compUuid);

}
