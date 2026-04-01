package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_LIBRARY;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class LibraryEndpointService extends AbstractCRUDEndpointService<LibraryDao, Library> {

	@Inject
	public LibraryEndpointService(LibraryDao libraryDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(libraryDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_LIBRARY, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_LIBRARY, modelBuilder::toLibraryList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_LIBRARY, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_LIBRARY, () -> {
			LibraryCreateRequest request = lrc.requestBody(LibraryCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Library library = dao().createLibrary(userUuid, name);
			update(request::getMeta, library::setMeta);
			return library;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_LIBRARY, () -> {
			LibraryUpdateRequest request = lrc.requestBody(LibraryUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Library library = dao().load(id);
			// TOOD update
			update(request::getMeta, library::setMeta);
			update(request::getName, library::setName);
			setEditor(library, userUuid);
			return library;
		}, modelBuilder::toResponse);
	}

}
