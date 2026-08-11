package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;

public class EmbeddingModelBuilderTest extends AbstractModelBuilderTest {

	private static final UUID DETECTION_UUID = UUID.fromString("1c2c0f0e-8f27-4c2f-9b5a-6f1f7b0f7a11");

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
		// The response carries the whole vector identity, not just the uuid, so every field the
		// builder reads has to be stubbed here. An unstubbed getter yields the Mockito default and
		// the stored model would assert 0/false instead of the value that was written.
		when(embedding.getAssetUuid()).thenReturn(ASSET_UUID);
		when(embedding.getDetectionUuid()).thenReturn(DETECTION_UUID);
		when(embedding.getType()).thenReturn("face");
		when(embedding.getNodeKind()).thenReturn("facedetect");
		when(embedding.getModel()).thenReturn("inspireface-r18");
		when(embedding.getVector()).thenReturn(VECTOR_DATA);
		when(embedding.getDimensions()).thenReturn(VECTOR_DATA.length);
		when(embedding.getFrameNumber()).thenReturn(42);
		when(embedding.getSubjectIndex()).thenReturn(1);
		when(embedding.getNormalized()).thenReturn(true);
		mockMeta(embedding);
		// The response carries a creator/editor block; without a creator uuid the builder
		// leaves it empty (machine-written rows).
		mockCreatorEditorRefs(embedding);
		return embedding;
	}

}
