package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class LibraryModelTest implements ModelTestcases {

	@Test
	@Override
	public void testResponse() {
		assertModel(libraryResponse(), "LibraryResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(libraryCreateRequest(), "LibraryCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(libraryUpdateRequest(), "LibraryUpdateRequest");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(libraryListResponse(), "LibraryListResponse");
	}

	@Override
	public void testReference() {
		// Does not apply
	}

}
