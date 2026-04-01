package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ContentExamples.contentCreateRequest;
import static io.metaloom.loom.rest.model.example.ContentExamples.contentListResponse;
import static io.metaloom.loom.rest.model.example.ContentExamples.contentReference;
import static io.metaloom.loom.rest.model.example.ContentExamples.contentResponse;
import static io.metaloom.loom.rest.model.example.ContentExamples.contentUpdateRequest;
import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class ContentModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(contentResponse(), "ContentResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(contentCreateRequest(), "ContentCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(contentUpdateRequest(), "ContentUpdateRequest");
	}

	@Test
	@Override
	public void testReference() {
		assertModel(contentReference(), "ContentReference");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(contentListResponse(), "ContentListResponse");
	}


}
