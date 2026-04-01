package io.metaloom.loom.rest.model.embedding;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface EmbeddingExamples extends ExampleValues {

	default Example embeddingResponseExample() {
		return new ExampleImpl(embeddingResponse(), "The embedding response", HttpResponseStatus.OK);
	}

	default Example embeddingUpdateRequestExample() {
		return new ExampleImpl(embeddingUpdateRequest(), "The embedding update request", HttpResponseStatus.OK);
	}

	default Example embeddingCreateRequestExample() {
		return new ExampleImpl(embeddingCreateRequest(), "The embedding create request", HttpResponseStatus.CREATED);
	}

	default Example embeddingListResponseExample() {
		return new ExampleImpl(embeddingListResponse(), "The embedding list response", HttpResponseStatus.OK);
	}

	default EmbeddingResponse embeddingResponse() {
		EmbeddingResponse model = new EmbeddingResponse();
		model.setUuid(uuidA());
		model.setMeta(meta());
		return model;
	}

	default EmbeddingUpdateRequest embeddingUpdateRequest() {
		EmbeddingUpdateRequest model = new EmbeddingUpdateRequest();
		model.setType(EmbeddingType.VIDEO4J_FINGERPRINT_V2);
		model.setMeta(meta());
		return model;
	}

	default EmbeddingCreateRequest embeddingCreateRequest() {
		EmbeddingCreateRequest model = new EmbeddingCreateRequest();
		model.setVector(VECTOR_DATA);
		model.setMeta(meta());
		return model;
	}

	default EmbeddingListResponse embeddingListResponse() {
		EmbeddingListResponse list = new EmbeddingListResponse();
		list.add(embeddingResponse());
		list.add(embeddingResponse());
		list.setMetainfo(pagingInfo());
		return list;
	}
}
