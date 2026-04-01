package io.metaloom.loom.rest.model.library;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class LibraryListResponse extends AbstractListResponse<LibraryListResponse, LibraryResponse> {

	@Override
	public LibraryListResponse self() {
		return this;
	}

}
