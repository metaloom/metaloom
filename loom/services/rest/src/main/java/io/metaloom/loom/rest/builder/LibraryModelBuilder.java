package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;

public interface LibraryModelBuilder extends ModelBuilder, UserModelBuilder {

	default LibraryResponse toResponse(Library library) {
		LibraryResponse response = new LibraryResponse();
		response.setUuid(library.getUuid());
		response.setName(library.getName());
		setStatus(library, response);
		return response;
	}

	default LibraryListResponse toLibraryList(Page<Library> page) {
		return setPage(new LibraryListResponse(), page, this::toResponse);
	}

}
