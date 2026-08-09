package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_LIBRARY;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_LIBRARY;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.library.LibraryAssetRequest;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class LibraryEndpointService extends AbstractCRUDEndpointService<LibraryDao, Library> {

	private final BinaryStorageResolver storageResolver;

	@Inject
	public LibraryEndpointService(LibraryDao libraryDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		BinaryStorageResolver storageResolver) {
		super(libraryDao, daos, modelBuilder, validator);
		this.storageResolver = storageResolver;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_LIBRARY, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_LIBRARY, this::toLibraryList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_LIBRARY, () -> {
			return dao().load(id);
		}, this::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_LIBRARY, () -> {
			LibraryCreateRequest request = lrc.requestBody(LibraryCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Library library = dao().createLibrary(userUuid, name);
			library.setPoolUuid(requirePool(request.getPoolUuid()));
			update(request::getMeta, library::setMeta);
			return library;
		}, this::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_LIBRARY, () -> {
			LibraryUpdateRequest request = lrc.requestBody(LibraryUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Library library = dao().load(id);
			update(request::getMeta, library::setMeta);
			update(request::getName, library::setName);
			if (request.getPoolUuid() != null) {
				// Only affects uploads made after this point. Bytes already written to the previous pool
				// stay where they are and keep resolving, because each asset_location row records the pool
				// it used rather than deriving it from the library at read time.
				library.setPoolUuid(requirePool(request.getPoolUuid()));
			}
			setEditor(library, userUuid);
			return library;
		}, this::toResponse);
	}

	/**
	 * Add one asset to the library.
	 *
	 * <p>
	 * Writes the organizational membership only. Where the asset's bytes live is {@code asset_location} and is not touched here - an asset can be a
	 * member of a library it holds no binary in.
	 * </p>
	 *
	 * <p>
	 * Answers <b>201</b> for a new membership and <b>200</b> when the asset was already a member.
	 * </p>
	 */
	public void addAsset(LoomRoutingContext lrc, UUID libraryUuid) {
		checkPerm(lrc, UPDATE_LIBRARY, () -> {
			LibraryAssetRequest request = lrc.requestBody(LibraryAssetRequest.class);

			Library library = loadLibrary(libraryUuid);
			UUID assetUuid = request.getAssetUuid();
			if (assetUuid == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The assetUuid field must be set");
			}
			Asset asset = loadAsset(assetUuid);

			boolean alreadyMember = dao().containsAsset(library.getUuid(), asset.getUuid());
			dao().linkAsset(library.getUuid(), asset.getUuid());
			lrc.send(toResponse(library), alreadyMember ? 200 : 201);
		});
	}

	public void removeAsset(LoomRoutingContext lrc, UUID libraryUuid, UUID assetUuid) {
		checkPerm(lrc, UPDATE_LIBRARY, () -> {
			Library library = loadLibrary(libraryUuid);
			Asset asset = loadAsset(assetUuid);
			if (!dao().containsAsset(library.getUuid(), asset.getUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"Asset " + assetUuid + " is not a member of library " + libraryUuid);
			}
			dao().unlinkAsset(library.getUuid(), asset.getUuid());
			lrc.sendNoContent();
		});
	}

	public void listAssets(LoomRoutingContext lrc, UUID libraryUuid) {
		checkPerm(lrc, READ_LIBRARY, () -> {
			Library library = loadLibrary(libraryUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<Asset> page = daos().assetDao().loadPageByLibrary(library.getUuid(), paging.from(), paging.limit());
			lrc.send(modelBuilder.toAssetList(page));
		});
	}

	public void listLibrariesOfAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_LIBRARY, () -> {
			Asset asset = loadAsset(assetUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<Library> page = dao().loadPageByAsset(asset.getUuid(), paging.from(), paging.limit());
			lrc.send(toLibraryList(page));
		});
	}

	private Library loadLibrary(UUID libraryUuid) {
		Library library = dao().load(libraryUuid);
		if (library == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Library not found " + libraryUuid);
		}
		return library;
	}

	private Asset loadAsset(UUID assetUuid) {
		Asset asset = assetUuid == null ? null : daos().assetDao().load(assetUuid);
		if (asset == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetUuid);
		}
		return asset;
	}

	/**
	 * Reject a pool that does not exist, at the point the library is edited.
	 *
	 * <p>
	 * The foreign key would catch it too, but as a driver error at flush time. A library pointing at a missing pool is also the one failure that
	 * would not surface until somebody uploads into it, which could be much later.
	 * </p>
	 */
	private UUID requirePool(UUID poolUuid) {
		if (poolUuid == null) {
			return null;
		}
		if (daos().assetPoolDao().load(poolUuid) == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "No storage pool found for uuid " + poolUuid);
		}
		return poolUuid;
	}

	/**
	 * Add the derived {@code storageType} that the model builder cannot supply on its own — it is a stateless interface, and the answer needs the
	 * {@code asset_pool} row.
	 */
	private LibraryResponse toResponse(Library library) {
		LibraryResponse response = modelBuilder.toResponse(library);
		response.setStorageType(storageResolver.storageTypeOfPool(library.getPoolUuid()));
		return response;
	}

	private LibraryListResponse toLibraryList(Page<Library> page) {
		return modelBuilder.setPage(new LibraryListResponse(), page, this::toResponse);
	}

}
