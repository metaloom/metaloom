package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_REMIX;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.remix.RemixCreateRequest;
import io.metaloom.loom.rest.model.remix.RemixMemberRequest;
import io.metaloom.loom.rest.model.remix.RemixUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Remixes - named groups of assets that are versions of one another.
 *
 * <p>
 * Every route that exposes the <em>members</em> of a remix demands {@code READ_ASSET} alongside
 * {@code READ_REMIX}. A remix response on its own names no asset except its source, but the member
 * list carries filenames and hashes, so gating it on the remix permission alone would let a curator
 * enumerate assets they cannot otherwise see.
 * </p>
 */
@Singleton
public class RemixEndpointService extends AbstractCRUDEndpointService<RemixDao, Remix> {

	@Inject
	public RemixEndpointService(RemixDao remixDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(remixDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_REMIX, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_REMIX, page -> modelBuilder.toRemixList(page, dao()::countAssets));
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, READ_REMIX, () -> {
			Remix remix = loadRemix(id);
			lrc.send(modelBuilder.toResponse(remix, dao().countAssets(remix.getUuid())));
		});
	}

	/**
	 * Create a remix, and link the assets the request came with.
	 *
	 * <p>
	 * Members are accepted here rather than only on the membership route because the calling gesture
	 * is "combine these into a remix": splitting it into two calls would leave an empty remix behind
	 * whenever the second one failed. Adding members needs {@code UPDATE_REMIX} on its own route, but
	 * a caller allowed to create a remix is by construction allowed to populate the one it just made.
	 * </p>
	 */
	@Override
	public void create(LoomRoutingContext lrc) {
		checkPerms(lrc, () -> {
			RemixCreateRequest request = lrc.requestBody(RemixCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Remix remix = dao().createRemix(userUuid, request.getName());
			update(request::getDescription, remix::setDescription);
			update(request::getMeta, remix::setMeta);
			dao().store(remix);

			UUID source = request.getSourceAssetUuid();
			for (UUID assetUuid : request.getAssetUuids()) {
				Asset asset = loadAsset(assetUuid);
				RemixRole role = asset.getUuid().equals(source) ? RemixRole.SOURCE : RemixRole.DERIVED;
				dao().linkAsset(remix.getUuid(), asset.getUuid(), role, null, userUuid);
			}

			lrc.send(modelBuilder.toResponse(dao().load(remix.getUuid()), dao().countAssets(remix.getUuid())), 201);
		}, CREATE_REMIX, READ_ASSET);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, UPDATE_REMIX, () -> {
			RemixUpdateRequest request = lrc.requestBody(RemixUpdateRequest.class);
			validator.validate(request);

			Remix remix = loadRemix(id);
			update(request::getName, remix::setName);
			update(request::getDescription, remix::setDescription);
			update(request::getMeta, remix::setMeta);
			setEditor(remix, lrc.userUuid());
			dao().update(remix);

			// The source moves through the DAO rather than by writing the column: it has to demote the
			// incumbent member in the same transaction, which a plain field update would not do.
			if (request.getSourceAssetUuid() != null) {
				setSourceOrFail(remix.getUuid(), request.getSourceAssetUuid());
			}

			lrc.send(modelBuilder.toResponse(dao().load(remix.getUuid()), dao().countAssets(remix.getUuid())));
		});
	}

	/**
	 * Add assets to an existing remix.
	 *
	 * <p>
	 * Idempotent by construction: an asset that is already a member has its membership rewritten
	 * rather than rejected, so a client re-submitting a selection does not have to diff it first.
	 * </p>
	 */
	public void addAssets(LoomRoutingContext lrc, UUID remixUuid) {
		checkPerms(lrc, () -> {
			RemixMemberRequest request = lrc.requestBody(RemixMemberRequest.class);
			validator.validate(request);

			Remix remix = loadRemix(remixUuid);
			UUID userUuid = lrc.userUuid();
			for (UUID assetUuid : request.getAssetUuids()) {
				Asset asset = loadAsset(assetUuid);
				dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, userUuid);
			}
			if (request.getSourceAssetUuid() != null) {
				setSourceOrFail(remix.getUuid(), request.getSourceAssetUuid());
			}

			lrc.send(modelBuilder.toResponse(dao().load(remix.getUuid()), dao().countAssets(remix.getUuid())));
		}, UPDATE_REMIX, READ_ASSET);
	}

	public void removeAsset(LoomRoutingContext lrc, UUID remixUuid, UUID assetUuid) {
		checkPerm(lrc, UPDATE_REMIX, () -> {
			Remix remix = loadRemix(remixUuid);
			if (!dao().containsAsset(remix.getUuid(), assetUuid)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"Asset " + assetUuid + " is not a member of remix " + remixUuid);
			}
			dao().unlinkAsset(remix.getUuid(), assetUuid);
			lrc.sendNoContent();
		});
	}

	public void setSource(LoomRoutingContext lrc, UUID remixUuid) {
		checkPerm(lrc, UPDATE_REMIX, () -> {
			RemixMemberRequest request = lrc.requestBody(RemixMemberRequest.class);
			Remix remix = loadRemix(remixUuid);
			setSourceOrFail(remix.getUuid(), request.getSourceAssetUuid());
			lrc.send(modelBuilder.toResponse(dao().load(remix.getUuid()), dao().countAssets(remix.getUuid())));
		});
	}

	public void listMembers(LoomRoutingContext lrc, UUID remixUuid) {
		checkPerms(lrc, () -> {
			Remix remix = loadRemix(remixUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<RemixMember> page = dao().loadMembers(remix.getUuid(), paging.from(), paging.limit());
			lrc.send(modelBuilder.toRemixMemberList(page));
		}, READ_REMIX, READ_ASSET);
	}

	public void listRemixesOfAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerms(lrc, () -> {
			Asset asset = loadAsset(assetUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<Remix> page = dao().loadPageByAsset(asset.getUuid(), paging.from(), paging.limit());
			lrc.send(modelBuilder.toRemixList(page, dao()::countAssets));
		}, READ_REMIX, READ_ASSET);
	}

	/**
	 * Translate the DAO's "not a member" refusal into a 400.
	 *
	 * <p>
	 * It is a bad request, not an internal error: the caller named an asset that is not in this remix.
	 * Letting the {@link IllegalArgumentException} escape would surface it as a 500.
	 * </p>
	 */
	private void setSourceOrFail(UUID remixUuid, UUID assetUuid) {
		try {
			dao().setSource(remixUuid, assetUuid);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, e.getMessage());
		}
	}

	private Remix loadRemix(UUID remixUuid) {
		Remix remix = remixUuid == null ? null : dao().load(remixUuid);
		if (remix == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Remix not found " + remixUuid);
		}
		return remix;
	}

	private Asset loadAsset(UUID assetUuid) {
		Asset asset = assetUuid == null ? null : daos().assetDao().load(assetUuid);
		if (asset == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetUuid);
		}
		return asset;
	}
}
