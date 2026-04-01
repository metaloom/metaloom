package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;

public interface EmbeddingModelBuilder extends ModelBuilder, UserModelBuilder {

	default EmbeddingResponse toResponse(Embedding embedding) {
		EmbeddingResponse response = new EmbeddingResponse();
		response.setUuid(embedding.getUuid());
		response.setMeta(embedding.getMeta());
		setStatus(embedding, response);
		return response;
	}

	default EmbeddingListResponse toEmbeddingList(Page<Embedding> page) {
		return setPage(new EmbeddingListResponse(), page, this::toResponse);
	}
}
