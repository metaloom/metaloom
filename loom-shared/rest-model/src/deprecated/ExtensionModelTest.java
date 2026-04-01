package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ExtensionExamples.extensionCreateRequest;
import static io.metaloom.loom.rest.model.example.ExtensionExamples.extensionListResponse;
import static io.metaloom.loom.rest.model.example.ExtensionExamples.extensionResponse;
import static io.metaloom.loom.rest.model.example.ExtensionExamples.extensionUpdateRequest;
import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class ExtensionModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(extensionResponse(), "ExtensionResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(extensionCreateRequest(), "ExtensionCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(extensionUpdateRequest(), "ExtensionUpdateRequest");
	}

	@Override
	public void testReference() {
		// Does not apply to extensions
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(extensionListResponse(), "ExtensionListResponse");
	}
}
