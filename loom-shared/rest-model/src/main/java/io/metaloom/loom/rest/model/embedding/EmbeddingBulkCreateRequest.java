package io.metaloom.loom.rest.model.embedding;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Create many embeddings for one asset in a single call.
 *
 * <p>
 * A node that finds ten faces in a frame has ten vectors to write, and one round trip per vector is not a sensible shape for a worker that is already
 * holding the frame in memory. Mirrors {@code DetectionBulkCreateRequest}, and the two are meant to be used together: the detections go first, the
 * embeddings follow carrying the detection uuids the first call returned.
 * </p>
 */
public class EmbeddingBulkCreateRequest implements RestRequestModel {

	private List<EmbeddingCreateRequest> embeddings = new ArrayList<>();

	public List<EmbeddingCreateRequest> getEmbeddings() {
		return embeddings;
	}

	public EmbeddingBulkCreateRequest setEmbeddings(List<EmbeddingCreateRequest> embeddings) {
		this.embeddings = embeddings;
		return this;
	}

	public EmbeddingBulkCreateRequest add(EmbeddingCreateRequest embedding) {
		this.embeddings.add(embedding);
		return this;
	}

}
