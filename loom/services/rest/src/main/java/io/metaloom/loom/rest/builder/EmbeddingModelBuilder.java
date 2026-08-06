package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;

public interface EmbeddingModelBuilder extends ModelBuilder, UserModelBuilder {

	/**
	 * Build the response for an embedding.
	 *
	 * <p>
	 * This used to return the uuid and the meta and nothing else, so reading an embedding back told the caller neither what it was nor what it
	 * contained - the vector, the type, the model and the owning asset were all dropped. They are carried now; a read that cannot round-trip its own
	 * write is not much of a read.
	 * </p>
	 */
	default EmbeddingResponse toResponse(Embedding embedding) {
		EmbeddingResponse response = new EmbeddingResponse();
		response.setUuid(embedding.getUuid());
		response.setMeta(embedding.getMeta());
		response.setType(embedding.getType());
		response.setVector(embedding.getVector());
		response.setAssetUuid(embedding.getAssetUuid());
		response.setSource(embedding.getNodeKind());
		response.setModel(embedding.getModel());
		response.setDimensions(embedding.getDimensions());
		response.setDetectionUuid(embedding.getDetectionUuid());
		response.setFrameNumber(embedding.getFrameNumber());
		response.setSubjectIndex(embedding.getSubjectIndex());
		response.setNormalized(embedding.getNormalized());
		setStatus(embedding, response);
		return response;
	}

	default EmbeddingListResponse toEmbeddingList(Page<Embedding> page) {
		return setPage(new EmbeddingListResponse(), page, this::toResponse);
	}
}
