package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationCreateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationListResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationUpdateRequest;

public interface AssetLocationMethods {

	LoomClientRequest<AssetLocationResponse> loadLocation(UUID locationUuid);

	LoomClientRequest<NoResponse> deleteLocation(UUID locationUuid);

	LoomClientRequest<AssetLocationResponse> createLocation(AssetLocationCreateRequest request);

	LoomClientRequest<AssetLocationResponse> updateLocation(UUID locationUuid, AssetLocationUpdateRequest request);

	LoomClientRequest<AssetLocationListResponse> listLocations();

}
