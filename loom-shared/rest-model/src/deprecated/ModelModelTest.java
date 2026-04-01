package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelExamples.modelCreateRequest;
import static io.metaloom.loom.rest.model.example.ModelExamples.modelListResponse;
import static io.metaloom.loom.rest.model.example.ModelExamples.modelReference;
import static io.metaloom.loom.rest.model.example.ModelExamples.modelResponse;
import static io.metaloom.loom.rest.model.example.ModelExamples.modelUpdateRequest;
import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class ModelModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(modelResponse(), "ModelResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(modelCreateRequest(), "ModelCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(modelUpdateRequest(), "ModelUpdateRequest");
	}

	@Test
	@Override
	public void testReference() {
		assertModel(modelReference(), "ModelReference");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(modelListResponse(), "ModelListResponse");
	}

}
