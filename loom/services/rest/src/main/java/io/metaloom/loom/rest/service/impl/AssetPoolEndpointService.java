package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET_POOL;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ASSET_POOL;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_POOL;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET_POOL;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class AssetPoolEndpointService extends AbstractCRUDEndpointService<AssetPoolDao, AssetPool> {

	private static final Logger log = LoggerFactory.getLogger(AssetPoolEndpointService.class);

	@Inject
	public AssetPoolEndpointService(AssetPoolDao assetPoolDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(assetPoolDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ASSET_POOL, uuid);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ASSET_POOL, () -> {
			AssetPoolCreateRequest request = lrc.requestBody(AssetPoolCreateRequest.class);
			validator.validate(request);

			UUID creatorUuid = lrc.userUuid();
			AssetPool pool = dao().createAssetPool(creatorUuid, request.getName());
			update(request::getFsPath, pool::setFsPath);
			update(request::getS3Bucket, pool::setS3Bucket);
			update(request::getS3Region, pool::setS3Region);
			update(request::getS3Endpoint, pool::setS3Endpoint);
			update(request::getFreeSpace, pool::setFreeSpace);
			update(request::getUsedSpace, pool::setUsedSpace);
			update(request::getMeta, pool::setMeta);
			return pool;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_ASSET_POOL, () -> {
			AssetPoolUpdateRequest request = lrc.requestBody(AssetPoolUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			AssetPool pool = dao().load(uuid);
			update(request::getName, pool::setName);
			update(request::getFsPath, pool::setFsPath);
			update(request::getS3Bucket, pool::setS3Bucket);
			update(request::getS3Region, pool::setS3Region);
			update(request::getS3Endpoint, pool::setS3Endpoint);
			update(request::getFreeSpace, pool::setFreeSpace);
			update(request::getUsedSpace, pool::setUsedSpace);
			update(request::getMeta, pool::setMeta);
			setEditor(pool, userUuid);
			return pool;
		}, modelBuilder::toResponse);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ASSET_POOL, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ASSET_POOL, modelBuilder::toPoolList);
	}

}
