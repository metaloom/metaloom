package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;

public interface AssetBinaryMethods {

	LoomClientRequest<AssetBinaryResponse> loadBinary(UUID binaryUuid);

	LoomClientRequest<NoResponse> deleteBinary(UUID binaryUuid);

	LoomClientRequest<AssetBinaryResponse> createBinary(AssetBinaryCreateRequest request);

	LoomClientRequest<AssetBinaryResponse> updateBinary(UUID binaryUuid, AssetBinaryUpdateRequest request);

	LoomClientRequest<AssetBinaryListResponse> listBinaries();

	LoomClientRequest<AssetBinaryResponse> loadAssetBinary(UUID assetUuid);

	LoomClientRequest<AssetBinaryResponse> createAssetBinary(UUID assetUuid, AssetBinaryCreateRequest request);

	LoomClientRequest<NoResponse> deleteAssetBinary(UUID assetUuid);

}
