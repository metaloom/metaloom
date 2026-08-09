package io.metaloom.loom.rest.model.searchindex;

import java.time.Instant;
import java.util.List;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * OpenAPI examples for the search index admin routes.
 *
 * <p>
 * The list example deliberately shows all three kinds at once, in three different states - a healthy lexical index, a face space with a small
 * backlog, and a semantic space that is configured but has no inference host - because the shape of the response is hard to read from the schema
 * alone and those three cases are what a client has to render.
 * </p>
 */
public interface SearchIndexExamples extends ExampleValues {

	default Example searchIndexListResponseExample() {
		return new ExampleImpl(searchIndexListResponse(), "The search index list response", HttpResponseStatus.OK);
	}

	default Example searchIndexResponseExample() {
		return new ExampleImpl(searchIndexResponse(), "The search index response", HttpResponseStatus.OK);
	}

	default Example indexJobCreateRequestExample() {
		return new ExampleImpl(indexJobCreateRequest(), "The index job create request", HttpResponseStatus.ACCEPTED);
	}

	default Example indexJobResponseExample() {
		return new ExampleImpl(indexJobResponse(), "The accepted index job", HttpResponseStatus.ACCEPTED);
	}

	default Example indexJobListResponseExample() {
		return new ExampleImpl(indexJobListResponse(), "The index job list response", HttpResponseStatus.OK);
	}

	default SearchIndexListResponse searchIndexListResponse() {
		SearchIndexListResponse model = new SearchIndexListResponse();

		model.getData().add(new SearchIndexResponse()
			.setId("lexical")
			.setKind("LEXICAL")
			.setBackendId("lexical")
			.setLabel("Lexical search documents")
			.setEnabled(true)
			.setAvailable(true)
			.setReason("Maintained synchronously by database triggers.")
			.setDocumentCount(41_286)
			.setIndexedCount(41_286)
			.setPendingCount(0)
			.setSupportedActions(List.of("REINDEX")));

		model.getData().add(searchIndexResponse());

		// Configured but with no reachable embedding host: enabled and unavailable, which is an
		// operational fault, as opposed to never switched on, which is a choice.
		model.getData().add(new SearchIndexResponse()
			.setId("vector-text-nomic-embed-text-v1-5-768")
			.setKind("VECTOR")
			.setBackendId("vector")
			.setLabel("Semantic text embeddings")
			.setType("text")
			.setModel("nomic-embed-text-v1.5")
			.setDimensions(768)
			.setEnabled(true)
			.setAvailable(false)
			.setReason("The vector index is configured but could not be opened.")
			.setDocumentCount(0)
			.setIndexedCount(0)
			.setPendingCount(41_286)
			.setSupportedActions(List.of("REINDEX", "DELTA_SYNC", "DROP")));

		model.getData().add(new SearchIndexResponse()
			.setId("fingerprint")
			.setKind("FINGERPRINT")
			.setBackendId("fingerprint")
			.setLabel("Duplicate fingerprints")
			.setAlgorithm("metaloom-multisector-v1")
			.setEnabled(true)
			.setAvailable(true)
			.setDocumentCount(9_140)
			.setIndexedCount(9_140)
			.setPendingCount(0)
			.setSupportedActions(List.of("REINDEX", "DELTA_SYNC", "DROP")));

		model.getBackends().add(new SearchIndexBackendResponse()
			.setId("lexical")
			.setProvider("postgres")
			.setEnabled(true)
			.setAvailable(true)
			.setDocumentCount(41_286)
			.setSizeBytes(612_368_384L));

		model.getBackends().add(new SearchIndexBackendResponse()
			.setId("vector")
			.setProvider("lucene")
			.setEnabled(true)
			.setAvailable(true)
			.setDocumentCount(128_402)
			.setDeletedCount(3_100)
			.setSizeBytes(1_476_395_008L));

		model.getBackends().add(new SearchIndexBackendResponse()
			.setId("fingerprint")
			.setProvider("lucene")
			.setEnabled(true)
			.setAvailable(true)
			.setDocumentCount(9_140)
			.setSizeBytes(37_748_736L));

		return model;
	}

	default SearchIndexResponse searchIndexResponse() {
		return new SearchIndexResponse()
			.setId("vector-face-inspireface-r18-512")
			.setKind("VECTOR")
			.setBackendId("vector")
			.setLabel("Face embeddings")
			.setType("face")
			.setModel("inspireface-r18")
			.setDimensions(512)
			.setEnabled(true)
			.setAvailable(true)
			.setDocumentCount(128_402)
			.setIndexedCount(127_590)
			.setPendingCount(812)
			.setSupportedActions(List.of("REINDEX", "DELTA_SYNC", "DROP"));
	}

	default IndexJobCreateRequest indexJobCreateRequest() {
		return new IndexJobCreateRequest().setAction("REINDEX");
	}

	default IndexJobResponse indexJobResponse() {
		return new IndexJobResponse()
			.setUuid(uuidA())
			.setIndexId("vector-face-inspireface-r18-512")
			.setAction("REINDEX")
			.setState("RUNNING")
			.setProcessed(18_420)
			.setTotal(128_402L)
			.setStartedAt(Instant.parse("2026-08-09T10:15:00Z"));
	}

	default IndexJobListResponse indexJobListResponse() {
		IndexJobListResponse model = new IndexJobListResponse();
		model.getData().add(indexJobResponse());
		model.getData().add(new IndexJobResponse()
			.setUuid(uuidB())
			.setIndexId("vector-face-inspireface-r18-512")
			.setAction("DELTA_SYNC")
			.setState("SUCCEEDED")
			.setProcessed(812)
			.setTotal(812L)
			.setRemoved(4)
			.setStartedAt(Instant.parse("2026-08-09T09:00:00Z"))
			.setFinishedAt(Instant.parse("2026-08-09T09:00:12Z")));
		return model;
	}
}
