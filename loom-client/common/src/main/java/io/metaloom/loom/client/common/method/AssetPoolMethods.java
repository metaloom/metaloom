package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolUpdateRequest;

public interface AssetPoolMethods {

	LoomClientRequest<AssetPoolResponse> loadPool(UUID poolUuid);

	LoomClientRequest<NoResponse> deletePool(UUID poolUuid);

	LoomClientRequest<AssetPoolResponse> createPool(AssetPoolCreateRequest request);

	LoomClientRequest<AssetPoolResponse> updatePool(UUID poolUuid, AssetPoolUpdateRequest request);

	LoomClientRequest<AssetPoolListResponse> listPools();

}
