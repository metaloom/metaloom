package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;

public interface EmbeddingModelValidator  extends ModelValidator {

	
	default void validate(EmbeddingUpdateRequest request) {

	}

	default void validate(EmbeddingResponse response) {
		validateCreatorEditorResponse(response);
	}

	/**
	 * A time window that ends before it starts violates the table's {@code embedding_range_check}. Rejecting it here turns what used to be a 500 from
	 * the database into a 400 - and, in a bulk write, into one failed item rather than a failed batch.
	 */
	default void validate(EmbeddingCreateRequest request) {
		Long from = request.getTimeFrom();
		Long to = request.getTimeTo();
		if (from != null && from < 0) {
			throw new ValidationException("timeFrom must not be negative, got " + from);
		}
		if (from != null && to != null && to < from) {
			throw new ValidationException("timeTo must not precede timeFrom, got " + from + " - " + to);
		}
	}
}
