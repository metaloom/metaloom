package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class EmbeddingModelAssert extends AbstractModelAssert<EmbeddingModelAssert, EmbeddingResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public EmbeddingModelAssert(EmbeddingResponse actual) {
		super(actual, EmbeddingModelAssert.class);
	}

	public EmbeddingModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public EmbeddingModelAssert matches(EmbeddingResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The uuid did not match");
		return this;
	}

}
