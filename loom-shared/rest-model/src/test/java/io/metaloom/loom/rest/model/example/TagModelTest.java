package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class TagModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(tagResponse(), "TagResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(tagCreateRequest(), "TagCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(tagUpdateRequest(), "TagUpdateRequest");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(tagListResponse(), "TagListResponse");
	}

	@Test
	@Override
	public void testReference() {
		assertModel(tagReference(), "TagReference");
	}

}
