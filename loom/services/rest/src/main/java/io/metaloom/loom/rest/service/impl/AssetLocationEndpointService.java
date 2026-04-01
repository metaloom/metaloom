package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET_LOCATION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ASSET_LOCATION;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_LOCATION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET_LOCATION;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.location.AssetLocationCreateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationFilesystemInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class AssetLocationEndpointService extends AbstractCRUDEndpointService<AssetLocationDao, AssetLocation> {

	private static final Logger log = LoggerFactory.getLogger(AssetEndpointService.class);

	@Inject
	public AssetLocationEndpointService(AssetLocationDao assetLocationDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(assetLocationDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ASSET_LOCATION, uuid);
	}

	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ASSET_LOCATION, () -> {
			AssetLocationCreateRequest request = lrc.requestBody(AssetLocationCreateRequest.class);
			if (request.getFilesystem() != null) {
				AssetLocationFilesystemInfo fsInfo = request.getFilesystem();
				String path = fsInfo.getPath();
				UUID creatorUuid = lrc.userUuid();
				UUID assetUuid = request.getAssetUuid();
				UUID libraryUuid = request.getLibraryUuid();
				AssetLocation location = dao().createAssetLocation(path, assetUuid, creatorUuid, libraryUuid);
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
		update(lrc, UPDATE_ASSET_LOCATION, () -> {
			AssetLocationUpdateRequest request = lrc.requestBody(AssetLocationUpdateRequest.class);
			UUID userUuid = lrc.userUuid();

			AssetLocation location = dao().load(uuid);
			// TODO update
			update(request::getMeta, location::setMeta);
			setEditor(location, userUuid);
			return location;
		}, modelBuilder::toResponse);
	}

	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ASSET_LOCATION, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ASSET_LOCATION, modelBuilder::toLocationList);
	}

//	public void listAssetLocations(LoomRoutingContext lrc, String sha512orUUID) {
//		checkPerm(lrc, READ_ASSET_LOCATION, () -> {
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
//			Page<AssetLocation> page = dao().loadPage(from, limit, filters, sortParameters.sortBy(), sortParameters.sortOrder());
//			AssetLocationListResponse response = modelBuilder.toLocationList(page);
//			lrc.send(response);
//		});
//	}
//
//	public void loadAssetLocation(LoomRoutingContext lrc, String sha512orUUID, UUID locationUUID) {
//		load(lrc, READ_ASSET_LOCATION, () -> {
//			return dao().load(locationUUID);
//		}, modelBuilder::toResponse);
//	}
//
//	public void createAssetLocation(LoomRoutingContext lrc, String sha512orUUID) {
//		create(lrc, CREATE_ASSET_LOCATION, () -> {
//			AssetLocationCreateRequest request = lrc.requestBody(AssetLocationCreateRequest.class);
//			if (request.getFilesystem() != null) {
//				AssetLocationFilesystemInfo fsInfo = request.getFilesystem();
//				String path = fsInfo.getPath();
//				UUID creatorUuid = lrc.userUuid();
//				Asset asset = daos().assetDao().loadBySHA512OrUuid(sha512orUUID);
//				UUID libraryUuid = request.getLibraryUuid();
//				AssetLocation location = dao().createAssetLocation(path, asset.getUuid(), creatorUuid, libraryUuid);
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
