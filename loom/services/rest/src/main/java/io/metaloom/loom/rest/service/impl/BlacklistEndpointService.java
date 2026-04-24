package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_BLACKLIST;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_BLACKLIST;
import static io.metaloom.loom.db.model.perm.Permission.READ_BLACKLIST;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_BLACKLIST;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistModel;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class BlacklistEndpointService extends AbstractCRUDEndpointService<BlacklistDao, Blacklist> {

	@Inject
	public BlacklistEndpointService(BlacklistDao blacklistDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(blacklistDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_BLACKLIST, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_BLACKLIST, modelBuilder::toBlacklistList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_BLACKLIST, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_BLACKLIST, () -> {
			BlacklistCreateRequest request = lrc.requestBody(BlacklistCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String name = request.getName();
			UUID assetUuid = request.getAssetUuid() != null ? UUID.fromString(request.getAssetUuid()) : null;
			Blacklist blacklist = dao().createBlacklist(userUuid, assetUuid, name);
			update(request, blacklist);
			return blacklist;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_BLACKLIST, () -> {
			BlacklistUpdateRequest request = lrc.requestBody(BlacklistUpdateRequest.class);
			validator.validate(request);

			Blacklist blacklist = dao().load(uuid);
			update(request, blacklist);
			return blacklist;
		}, modelBuilder::toResponse);
	}

	private void update(BlacklistModel<?> model, Blacklist blacklist) {
		update(model::getName, blacklist::setName);
		if (model.getAssetUuid() != null) {
			blacklist.setAssetUuid(UUID.fromString(model.getAssetUuid()));
		}
		update(model::getMeta, blacklist::setMeta);
	}
}
