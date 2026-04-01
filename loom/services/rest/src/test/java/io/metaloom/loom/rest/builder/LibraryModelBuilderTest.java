package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.library.LibraryListResponse;

public class LibraryModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Library library = mockLibrary("primary");
		assertWithModel(builder().toResponse(library), "library.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Library library1 = mockLibrary("primary");
		Library library2 = mockLibrary("secondary");
		Page<Library> page = mockPage(library1, library2);
		LibraryListResponse list = builder().toLibraryList(page);
		assertWithModel(list, "library.list_response");
	}

	private Library mockLibrary(String name) {
		Library library = mock(Library.class);
		when(library.getUuid()).thenReturn(LIBRARY_UUID);
		when(library.getName()).thenReturn(name);
		return library;
	}

}
