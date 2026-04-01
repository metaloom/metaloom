package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
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
}
