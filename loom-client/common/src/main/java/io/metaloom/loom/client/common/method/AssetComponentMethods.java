package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;

public interface AssetComponentMethods {

	LoomClientRequest<AssetComponentListResponse> listAssetComponents(UUID assetUuid);

	LoomClientRequest<AssetComponentResponse> createAssetComponent(UUID assetUuid, AssetComponentCreateRequest request);

	LoomClientRequest<AssetComponentResponse> loadAssetComponent(UUID assetUuid, UUID compUuid);

	LoomClientRequest<AssetComponentResponse> updateAssetComponent(UUID assetUuid, UUID compUuid, AssetComponentUpdateRequest request);

	LoomClientRequest<NoResponse> deleteAssetComponent(UUID assetUuid, UUID compUuid);
}
