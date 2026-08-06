package io.metaloom.loom.rest.model.embedding;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Outcome of a bulk embedding create.
 *
 * <p>
 * {@code total} counts what was sent, {@code created} what was written and {@code failed} what was rejected, so a partial success is reported as one
 * rather than mistaken for either a clean run or a total loss. Mirrors {@code DetectionBulkResponse}.
 * </p>
 */
public class EmbeddingBulkResponse implements RestResponseModel<EmbeddingBulkResponse> {

	private List<EmbeddingResponse> embeddings = new ArrayList<>();

	private int total;

	private int created;

	private int failed;

	public List<EmbeddingResponse> getEmbeddings() {
		return embeddings;
	}

	public EmbeddingBulkResponse setEmbeddings(List<EmbeddingResponse> embeddings) {
		this.embeddings = embeddings;
		return this;
	}

	public EmbeddingBulkResponse add(EmbeddingResponse response) {
		this.embeddings.add(response);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public EmbeddingBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getCreated() {
		return created;
	}

	public EmbeddingBulkResponse setCreated(int created) {
		this.created = created;
		return this;
	}

	public int getFailed() {
		return failed;
	}

	public EmbeddingBulkResponse setFailed(int failed) {
		this.failed = failed;
		return this;
	}

	@Override
	public EmbeddingBulkResponse self() {
		return this;
	}

}
