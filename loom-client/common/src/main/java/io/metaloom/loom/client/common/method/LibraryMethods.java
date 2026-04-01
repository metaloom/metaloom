package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;

public interface LibraryMethods {

	LoomClientRequest<LibraryResponse> loadLibrary(UUID uuid);

	LoomClientRequest<LibraryResponse> createLibrary(LibraryCreateRequest request);

	LoomClientRequest<LibraryResponse> updateLibrary(UUID uuid, LibraryUpdateRequest request);

	LoomClientRequest<LibraryListResponse> listLibraries();

	LoomClientRequest<NoResponse> deleteLibrary(UUID uuid);
}
