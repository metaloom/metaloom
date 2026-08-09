package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkRequest;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkResponse;
import io.metaloom.loom.rest.model.collection.CollectionAssetRequest;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;

public interface CollectionMethods {

	LoomClientRequest<CollectionResponse> loadCollection(UUID collectionUuid);

	LoomClientRequest<CollectionResponse> createCollection(CollectionCreateRequest request);

	LoomClientRequest<CollectionResponse> updateCollection(UUID collectionUuid, CollectionUpdateRequest request);

	LoomClientRequest<CollectionListResponse> listCollections();

	LoomClientRequest<NoResponse> deleteCollection(UUID collectionUuid);

	/**
	 * Add an asset to the collection.
	 *
	 * <p>
	 * Answers 201 when the asset became a new member and 200 when it already was one - both successes. Membership is a set, so re-adding is a no-op
	 * rather than an error, which is what lets a pipeline re-run over a curated corpus.
	 * </p>
	 *
	 * @param collectionUuid
	 * @param request
	 * @return
	 */
	LoomClientRequest<CollectionResponse> addCollectionAsset(UUID collectionUuid, CollectionAssetRequest request);

	default LoomClientRequest<CollectionResponse> addCollectionAsset(UUID collectionUuid, UUID assetUuid) {
		return addCollectionAsset(collectionUuid, new CollectionAssetRequest().setAssetUuid(assetUuid));
	}

	/**
	 * Add several assets to the collection in one call. An asset uuid that names nothing is counted in {@code failed}; the rest are still linked.
	 *
	 * @param collectionUuid
	 * @param request
	 * @return
	 */
	LoomClientRequest<CollectionAssetBulkResponse> addCollectionAssets(UUID collectionUuid, CollectionAssetBulkRequest request);

	LoomClientRequest<NoResponse> removeCollectionAsset(UUID collectionUuid, UUID assetUuid);

	LoomClientRequest<AssetListResponse> listCollectionAssets(UUID collectionUuid);

	/**
	 * List the collections the asset belongs to.
	 *
	 * @param assetUuid
	 * @return
	 */
	LoomClientRequest<CollectionListResponse> listAssetCollections(UUID assetUuid);

}
