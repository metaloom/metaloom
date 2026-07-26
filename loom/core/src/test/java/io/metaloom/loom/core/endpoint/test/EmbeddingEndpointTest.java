package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;

public class EmbeddingEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		EmbeddingResponse embedding = client.loadEmbedding(EMBEDDING_UUID).sync().body();
		assertThat(embedding).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		EmbeddingCreateRequest request = new EmbeddingCreateRequest();
		request.setVector(new Float[] { 0.42f, 0.24f });
		request.setType(EmbeddingType.VIDEO4J_FINGERPRINT_V1);
		request.setAssetUuid(ASSET_UUID);
		EmbeddingResponse response = client.createEmbedding(request).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteEmbedding(EMBEDDING_UUID).sync().body();
		expect(404, "Not Found", client.loadEmbedding(EMBEDDING_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		EmbeddingUpdateRequest request = new EmbeddingUpdateRequest();
		client.updateEmbedding(EMBEDDING_UUID, request).sync().body();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		EmbeddingListResponse response = client.listEmbeddings().sync().body();
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		EmbeddingCreateRequest request = new EmbeddingCreateRequest();
		request.setVector(new Float[] { 0.42f, 0.24f });
		request.setType(EmbeddingType.VIDEO4J_FINGERPRINT_V1);
		request.setAssetUuid(ASSET_UUID);
		return client.createEmbedding(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadEmbedding(EMBEDDING_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listEmbeddings();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteEmbedding(EMBEDDING_UUID);
	}

}
