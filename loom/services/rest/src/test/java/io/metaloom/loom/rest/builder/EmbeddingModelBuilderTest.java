package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;

public class EmbeddingModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Embedding embedding = mockEmbedding();
		assertWithModel(builder().toResponse(embedding), "embedding.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Embedding embedding = mockEmbedding();
		Page<Embedding> page = mockPage(embedding, embedding);
		EmbeddingListResponse list = builder().toEmbeddingList(page);
		assertWithModel(list, "embedding.list_response");
	}

	private Embedding mockEmbedding() {
		Embedding embedding = mock(Embedding.class);
		when(embedding.getUuid()).thenReturn(EMBEDDING_UUID);
		return embedding;
	}

}
