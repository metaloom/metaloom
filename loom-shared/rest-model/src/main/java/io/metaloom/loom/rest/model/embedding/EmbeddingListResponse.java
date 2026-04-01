package io.metaloom.loom.rest.model.embedding;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class EmbeddingListResponse extends AbstractListResponse<EmbeddingListResponse, EmbeddingResponse> {

	@Override
	public EmbeddingListResponse self() {
		return this;
	}

}
