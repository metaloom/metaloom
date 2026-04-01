package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class GroupModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(groupResponse(), "GroupResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(groupCreateRequest(), "GroupCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(groupUpdateRequest(), "GroupUpdateRequest");
	}

	@Test
	@Override
	public void testReference() {
		assertModel(groupReference(), "GroupReference");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(groupListResponse(), "GroupListResponse");
	}

}
