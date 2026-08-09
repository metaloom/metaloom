package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_COLLECTION;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkRequest;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkResponse;
import io.metaloom.loom.rest.model.collection.CollectionAssetRequest;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class CollectionEndpointService extends AbstractCRUDEndpointService<CollectionDao, Collection> {

	private static final Logger log = LoggerFactory.getLogger(CollectionEndpointService.class);

	@Inject
	public CollectionEndpointService(CollectionDao collectionDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(collectionDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_COLLECTION, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_COLLECTION, modelBuilder::toCollectionList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_COLLECTION, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_COLLECTION, () -> {
			CollectionCreateRequest request = lrc.requestBody(CollectionCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Collection collection = dao().createCollection(userUuid, name);
			update(request::getMeta, collection::setMeta);
			return collection;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_COLLECTION, () -> {
			CollectionUpdateRequest request = lrc.requestBody(CollectionUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Collection collection = dao().load(id);
			update(request::getName, collection::setName);
			update(request::getMeta, collection::setMeta);
			setEditor(collection, userUuid);
			return collection;
		}, modelBuilder::toResponse);
	}

	/**
	 * Add one asset to the collection.
	 *
	 * <p>
	 * Answers <b>201</b> when the asset became a new member and <b>200</b> when it already was one. Both are successes: membership is a set, so
	 * asking for something that is already true is not an error. A client that ignores the status code still behaves correctly; one that reports
	 * "added" on a 200 would over-count.
	 * </p>
	 */
	public void addAsset(LoomRoutingContext lrc, UUID collectionUuid) {
		checkPerm(lrc, UPDATE_COLLECTION, () -> {
			CollectionAssetRequest request = lrc.requestBody(CollectionAssetRequest.class);

			Collection collection = loadCollection(collectionUuid);
			UUID assetUuid = request.getAssetUuid();
			if (assetUuid == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The assetUuid field must be set");
			}
			Asset asset = loadAsset(assetUuid);

			boolean alreadyMember = dao().containsAsset(collection.getUuid(), asset.getUuid());
			dao().linkAsset(collection.getUuid(), asset.getUuid());
			lrc.send(modelBuilder.toResponse(collection), alreadyMember ? 200 : 201);
		});
	}

	/**
	 * Add several assets to the collection in one call.
	 *
	 * <p>
	 * Partial success is the contract: an asset uuid that names nothing is counted in {@code failed} and the remaining uuids are still linked. A
	 * curation run over a stale list should not lose the assets that do exist.
	 * </p>
	 */
	public void addAssets(LoomRoutingContext lrc, UUID collectionUuid) {
		checkPerm(lrc, UPDATE_COLLECTION, () -> {
			CollectionAssetBulkRequest request = lrc.requestBody(CollectionAssetBulkRequest.class);

			Collection collection = loadCollection(collectionUuid);
			List<UUID> assetUuids = request.getAssetUuids();

			CollectionAssetBulkResponse response = new CollectionAssetBulkResponse()
				.setTotal(assetUuids.size());
			int added = 0;
			for (UUID assetUuid : assetUuids) {
				Asset asset = assetUuid == null ? null : daos().assetDao().load(assetUuid);
				if (asset == null) {
					response.addFailed(assetUuid);
					continue;
				}
				if (!dao().containsAsset(collection.getUuid(), asset.getUuid())) {
					added++;
				}
				dao().linkAsset(collection.getUuid(), asset.getUuid());
			}
			response.setAdded(added);
			lrc.send(response);
		});
	}

	public void removeAsset(LoomRoutingContext lrc, UUID collectionUuid, UUID assetUuid) {
		checkPerm(lrc, UPDATE_COLLECTION, () -> {
			Collection collection = loadCollection(collectionUuid);
			Asset asset = loadAsset(assetUuid);
			if (!dao().containsAsset(collection.getUuid(), asset.getUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"Asset " + assetUuid + " is not a member of collection " + collectionUuid);
			}
			dao().unlinkAsset(collection.getUuid(), asset.getUuid());
			lrc.sendNoContent();
		});
	}

	public void listAssets(LoomRoutingContext lrc, UUID collectionUuid) {
		checkPerm(lrc, READ_COLLECTION, () -> {
			Collection collection = loadCollection(collectionUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<Asset> page = daos().assetDao().loadPageByCollection(collection.getUuid(), paging.from(), paging.limit());
			lrc.send(modelBuilder.toAssetList(page));
		});
	}

	public void listCollectionsOfAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_COLLECTION, () -> {
			Asset asset = loadAsset(assetUuid);
			PagingParameters paging = lrc.pagingParams();
			Page<Collection> page = dao().loadPageByAsset(asset.getUuid(), paging.from(), paging.limit());
			lrc.send(modelBuilder.toCollectionList(page));
		});
	}

	private Collection loadCollection(UUID collectionUuid) {
		Collection collection = dao().load(collectionUuid);
		if (collection == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Collection not found " + collectionUuid);
		}
		return collection;
	}

	private Asset loadAsset(UUID assetUuid) {
		Asset asset = assetUuid == null ? null : daos().assetDao().load(assetUuid);
		if (asset == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetUuid);
		}
		return asset;
	}
}
