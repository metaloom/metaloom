package io.metaloom.loom.client.common.method;

import java.io.File;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
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

	/**
	 * Load the asset's primary binary. An asset holds one binary per library it was imported into; this is the oldest.
	 *
	 * @param assetUuid
	 *            the asset
	 * @return the request
	 */
	LoomClientRequest<AssetBinaryResponse> loadAssetBinary(UUID assetUuid);

	/**
	 * List every binary of an asset — one per library it was imported into.
	 *
	 * @param assetUuid
	 *            the asset
	 * @return the request
	 */
	LoomClientRequest<AssetBinaryListResponse> listAssetBinaries(UUID assetUuid);

	LoomClientRequest<AssetBinaryResponse> createAssetBinary(UUID assetUuid, AssetBinaryCreateRequest request);

	LoomClientRequest<NoResponse> deleteAssetBinary(UUID assetUuid);

	/**
	 * Create an asset from a local file, uploading the bytes as {@code multipart/form-data}.
	 *
	 * @param file
	 *            the file to upload
	 * @param libraryUuid
	 *            target library; decides which storage pool receives the bytes
	 * @param mimeType
	 *            content type of the file, or null for {@code application/octet-stream}
	 * @return the request, yielding the created asset
	 */
	LoomClientRequest<AssetResponse> uploadAsset(File file, UUID libraryUuid, String mimeType);

	/**
	 * Upload raw bytes into an existing asset's binary, creating or replacing it.
	 *
	 * @param assetUuid
	 *            the asset
	 * @param file
	 *            the file to upload
	 * @param libraryUuid
	 *            target library. Required when the asset has no binary yet, and when it has more than one — otherwise the server cannot tell which
	 *            one to replace and answers 400
	 * @param mimeType
	 *            content type of the file, or null for {@code application/octet-stream}
	 * @return the request, yielding the stored binary
	 */
	LoomClientRequest<AssetBinaryResponse> uploadAssetBinary(UUID assetUuid, File file, UUID libraryUuid, String mimeType);

	/**
	 * Download the raw bytes of an asset's primary binary.
	 *
	 * @param assetUuid
	 *            the asset
	 * @return the request, yielding a streaming response the caller must close
	 */
	LoomClientRequest<LoomBinaryResponse> downloadAssetBinary(UUID assetUuid);

}
