package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;

public interface LibraryModelBuilder extends ModelBuilder, UserModelBuilder {

	/**
	 * Build the library response.
	 *
	 * <p>
	 * Note that {@code storageType} is <em>not</em> set here: deriving it needs the {@code asset_pool} row and this builder has no DAO.
	 * {@code LibraryEndpointService} fills it in. A library response reaching a client with a null {@code storageType} means some other call site
	 * bypassed that service.
	 * </p>
	 */
	default LibraryResponse toResponse(Library library) {
		LibraryResponse response = new LibraryResponse();
		response.setUuid(library.getUuid());
		response.setName(library.getName());
		response.setPoolUuid(library.getPoolUuid());
		setStatus(library, response);
		return response;
	}

	default LibraryListResponse toLibraryList(Page<Library> page) {
		return setPage(new LibraryListResponse(), page, this::toResponse);
	}

}
