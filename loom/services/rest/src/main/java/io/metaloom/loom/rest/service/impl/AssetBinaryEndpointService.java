package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET_BINARY;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;

@Singleton
public class AssetBinaryEndpointService extends AbstractCRUDEndpointService<AssetBinaryDao, AssetBinary> {

	private static final Logger log = LoggerFactory.getLogger(AssetBinaryEndpointService.class);

	private final FileSystem fileSystem;

	@Inject
	public AssetBinaryEndpointService(AssetBinaryDao assetLocationDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, FileSystem fileSystem) {
		super(assetLocationDao, daos, modelBuilder, validator);
		this.fileSystem = fileSystem;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ASSET_BINARY, uuid);
	}

	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ASSET_BINARY, () -> {
			AssetBinaryCreateRequest request = lrc.requestBody(AssetBinaryCreateRequest.class);
			if (request.getFilesystem() != null) {
				AssetBinaryFilesystemInfo fsInfo = request.getFilesystem();
				String path = fsInfo.getPath();
				UUID creatorUuid = lrc.userUuid();
				UUID assetUuid = request.getAssetUuid();
				UUID libraryUuid = request.getLibraryUuid();
				AssetBinary location = dao().createAssetBinary(path, assetUuid, creatorUuid, libraryUuid);
				update(request::getMeta, location::setMeta);
				return location;
			} else if (request.getS3() != null) {
				log.error("S3 support has not yet been implemented");
				lrc.error("S3 support not yet implemented");
				return null;
			} else {
				lrc.error("Unknown location information");
				return null;
			}
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_ASSET_BINARY, () -> {
			AssetBinaryUpdateRequest request = lrc.requestBody(AssetBinaryUpdateRequest.class);
			UUID userUuid = lrc.userUuid();

			AssetBinary location = dao().load(uuid);
			// TODO update
			update(request::getMeta, location::setMeta);
			setEditor(location, userUuid);
			return location;
		}, modelBuilder::toResponse);
	}

	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ASSET_BINARY, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ASSET_BINARY, modelBuilder::toBinaryList);
	}

	public void loadByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		load(lrc, READ_ASSET_BINARY, () -> {
			return dao().loadByAssetUuid(assetUuid);
		}, modelBuilder::toResponse);
	}

	/**
	 * Stream the raw binary bytes for the given asset. Sends the file directly on the underlying HTTP response (bypassing the JSON model builder) with
	 * the stored mime type and an {@code attachment} content disposition. Responds 404 when no binary row exists or the file is missing on disk.
	 *
	 * <p>
	 * Note: {@link HttpServerResponse#sendFile(String)} does not honour HTTP {@code Range} requests, so seek/scrubbing of large media is not supported
	 * by this route yet.
	 * </p>
	 */
	public void downloadByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			AssetBinary binary = dao().loadByAssetUuid(assetUuid);
			if (binary == null || binary.getPath() == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No binary found for asset.");
			}
			String path = binary.getPath();
			if (!fileSystem.existsBlocking(path)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Binary file is missing on disk.");
			}
			String mimeType = binary.getMimeType() != null ? binary.getMimeType() : "application/octet-stream";
			String fileName = java.nio.file.Paths.get(path).getFileName().toString();

			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader(HttpHeaders.CONTENT_TYPE, mimeType);
			response.putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
			response.sendFile(path);
		});
	}

	public void createForAsset(LoomRoutingContext lrc, UUID assetUuid) {
		create(lrc, CREATE_ASSET_BINARY, () -> {
			AssetBinaryCreateRequest request = lrc.requestBody(AssetBinaryCreateRequest.class);
			if (request.getFilesystem() != null) {
				AssetBinaryFilesystemInfo fsInfo = request.getFilesystem();
				String path = fsInfo.getPath();
				UUID creatorUuid = lrc.userUuid();
				UUID libraryUuid = request.getLibraryUuid();
				AssetBinary binary = dao().createAssetBinary(path, assetUuid, creatorUuid, libraryUuid);
				update(request::getMeta, binary::setMeta);
				return binary;
			} else if (request.getS3() != null) {
				log.error("S3 support has not yet been implemented");
				lrc.error("S3 support not yet implemented");
				return null;
			} else {
				lrc.error("Unknown binary information");
				return null;
			}
		}, modelBuilder::toResponse);
	}

	public void deleteByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		delete(lrc, DELETE_ASSET_BINARY, () -> {
			return dao().loadByAssetUuid(assetUuid);
		});
	}

//	public void listAssetBinarys(LoomRoutingContext lrc, String sha512orUUID) {
//		checkPerm(lrc, READ_ASSET_BINARY, () -> {
//			PagingParameters pagingParameters = lrc.pagingParams();
//			FilterParameters filterParameters = lrc.filterParams();
//			SortParameters sortParameters = lrc.sortParams();
//			UUID from = pagingParameters.from();
//			int limit = pagingParameters.limit();
//			if (log.isDebugEnabled()) {
//				log.debug("Loading page from {} limit: {}", from, limit);
//			}
//			List<Filter> filters = filterParameters.filters();
//			if (UUIDUtils.isUUID(sha512orUUID)) {
//				StringFilterKey ASSET_UUID = new StringFilterKey("uuid");
//				filters.add(ASSET_UUID.eq(sha512orUUID));
//			} else {
//				StringFilterKey ASSET_SHA512 = new StringFilterKey("sha512");
//				filters.add(ASSET_SHA512.eq(sha512orUUID));
//			}
//			Page<AssetBinary> page = dao().loadPage(from, limit, filters, sortParameters.sortBy(), sortParameters.sortOrder());
//			AssetBinaryListResponse response = modelBuilder.toLocationList(page);
//			lrc.send(response);
//		});
//	}
//
//	public void loadAssetBinary(LoomRoutingContext lrc, String sha512orUUID, UUID locationUUID) {
//		load(lrc, READ_ASSET_BINARY, () -> {
//			return dao().load(locationUUID);
//		}, modelBuilder::toResponse);
//	}
//
//	public void createAssetBinary(LoomRoutingContext lrc, String sha512orUUID) {
//		create(lrc, CREATE_ASSET_BINARY, () -> {
//			AssetBinaryCreateRequest request = lrc.requestBody(AssetBinaryCreateRequest.class);
//			if (request.getFilesystem() != null) {
//				AssetBinaryFilesystemInfo fsInfo = request.getFilesystem();
//				String path = fsInfo.getPath();
//				UUID creatorUuid = lrc.userUuid();
//				Asset asset = daos().assetDao().loadBySHA512OrUuid(sha512orUUID);
//				UUID libraryUuid = request.getLibraryUuid();
//				AssetBinary location = dao().createAssetBinary(path, asset.getUuid(), creatorUuid, libraryUuid);
//				update(request::getMeta, location::setMeta);
//				return location;
//			} else if (request.getS3() != null) {
//				lrc.error("S3 support not yet implemented");
//				return null;
//			} else {
//				lrc.error("Unknown location information");
//				return null;
//			}
//		}, modelBuilder::toResponse);
//	}
//
//

}
