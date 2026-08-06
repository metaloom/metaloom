package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;

public interface EmbeddingMethods {

	LoomClientRequest<EmbeddingResponse> loadEmbedding(UUID uuid);

	LoomClientRequest<EmbeddingResponse> createEmbedding(EmbeddingCreateRequest request);

	LoomClientRequest<EmbeddingResponse> updateEmbedding(UUID uuid, EmbeddingUpdateRequest request);

	LoomClientRequest<EmbeddingListResponse> listEmbeddings();

	LoomClientRequest<NoResponse> deleteEmbedding(UUID uuid);

	/**
	 * Create many embeddings for one asset in a single call.
	 *
	 * <p>
	 * Each item is upserted on its natural key, so a node that runs again rewrites its own rows instead of appending duplicates. Pair it with
	 * {@link DetectionMethods#bulkCreateAssetDetections}: write the detections first and carry the uuids it returns into
	 * {@code EmbeddingCreateRequest.detectionUuid}, so each vector points at the region it was computed from.
	 * </p>
	 */
	LoomClientRequest<EmbeddingBulkResponse> bulkCreateAssetEmbeddings(AssetId assetId, EmbeddingBulkCreateRequest request);

	default LoomClientRequest<EmbeddingBulkResponse> bulkCreateAssetEmbeddings(UUID assetUuid, EmbeddingBulkCreateRequest request) {
		return bulkCreateAssetEmbeddings(assetId(assetUuid), request);
	}
}
