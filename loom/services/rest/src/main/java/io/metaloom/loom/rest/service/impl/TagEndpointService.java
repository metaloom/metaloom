package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_TAG;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_TAG;
import static io.metaloom.loom.db.model.perm.Permission.READ_TAG;
import static io.metaloom.loom.db.model.perm.Permission.TAG_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UNTAG_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_TAG;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.tag.TagDao.AssetTagBulkResult;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.tag.AssetTagBulkRequest;
import io.metaloom.loom.rest.model.tag.AssetTagBulkResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagRatingRequest;
import io.metaloom.loom.rest.model.tag.TagRatingResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class TagEndpointService extends AbstractCRUDEndpointService<TagDao, Tag> {

	private static final Logger log = LoggerFactory.getLogger(TagEndpointService.class);

	@Inject
	public TagEndpointService(TagDao tagDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(tagDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_TAG, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_TAG, modelBuilder::toTagList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_TAG, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_TAG, () -> {
			TagCreateRequest request = lrc.requestBody(TagCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			String collection = request.getCollection();
			UUID userUuid = lrc.userUuid();
			Tag tag = dao().createTag(userUuid, name, collection);
			update(request::getMeta, tag::setMeta);
			return tag;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_TAG, () -> {
			TagUpdateRequest request = lrc.requestBody(TagUpdateRequest.class);
			validator.validate(request);

			Tag tag = dao().load(id);
			UUID userUuid = lrc.userUuid();
			update(request::getMeta, tag::setMeta);
			update(request::getName, tag::setName);
			update(request::getCollection, tag::setCollection);
			setEditor(tag, userUuid);
			return tag;
		}, modelBuilder::toResponse);
	}

	public void tagAsset(LoomRoutingContext lrc, AssetId assetId) {
		checkPerm(lrc, TAG_ASSET, () -> {
			TagCreateRequest request = lrc.requestBody(TagCreateRequest.class);
			validator.validate(request);

			Asset asset = daos().assetDao().loadById(assetId);
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetId);
			}

			String name = request.getName();
			String collection = request.getCollection();
			UUID userUuid = lrc.userUuid();
			AssetTag tag = dao().createAssetTag(userUuid, name, collection);
			update(request::getMeta, tag::setMeta);
			applyArea(request.getArea(), tag);
			applyProvenance(request, tag, userUuid, null, null, null);

			// Resolve rather than insert: (name, collection) is unique across the instance, so the second
			// asset to receive a tag name must attach the tag that already exists. A plain store() here
			// meant no caller could ever tag two assets alike.
			//
			// TAG_ASSET therefore implies creating the tag row when the name is new. That is deliberate:
			// requiring CREATE_TAG as well would mean a principal allowed to tag could not introduce a
			// tag, which is the ordinary case in a catalog. See PERMISSIONS.md.
			dao().resolveOrCreateAssetTag(tag);
			dao().tagAsset(tag, asset);

			RestResponseModel<?> response = modelBuilder.toResponse(tag);
			lrc.send(response, 201);
		});

	}

	/**
	 * Apply a whole set of tags to one asset in a single transaction, and detach the tags the caller names.
	 *
	 * <p>
	 * The scale route. Tagging one asset per request is correct and does not survive a library: five tags over a hundred thousand assets is half a
	 * million requests, half a million transactions, and half a million rebuilds of the same search document. Here it is one.
	 * </p>
	 *
	 * <p>
	 * Two permissions, and which ones depends on the request: attaching needs {@code TAG_ASSET}, and a request that also withdraws needs
	 * {@code UNTAG_ASSET} on top. A caller allowed to tag but not untag gets a 403 rather than a silently half-applied request - the whole call is
	 * one transaction, so it either happens or it does not.
	 * </p>
	 */
	public void bulkTagAsset(LoomRoutingContext lrc, AssetId assetId) {
		AssetTagBulkRequest request = lrc.requestBody(AssetTagBulkRequest.class);
		validator.validate(request);

		List<UUID> withdraw = request.getWithdraw() == null ? List.of() : request.getWithdraw();
		Permission[] required = withdraw.isEmpty()
			? new Permission[] { TAG_ASSET }
			: new Permission[] { TAG_ASSET, UNTAG_ASSET };

		checkPerms(lrc, () -> {
			Asset asset = daos().assetDao().loadById(assetId);
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetId);
			}

			UUID userUuid = lrc.userUuid();
			List<AssetTag> tags = new ArrayList<>();
			for (TagCreateRequest entry : request.getTags()) {
				// The request-level collection is the default for entries which do not name one; a writer
				// keeping all of its tags in one namespace should not have to repeat it per tag.
				String collection = entry.getCollection() == null ? request.getCollection() : entry.getCollection();
				AssetTag tag = dao().createAssetTag(userUuid, entry.getName(), collection);
				update(entry::getMeta, tag::setMeta);
				applyArea(entry.getArea(), tag);
				// The request-level provenance is the default for entries which do not carry their own, the
				// same way the request-level collection is: a worker states once who it is.
				applyProvenance(entry, tag, userUuid, request.getNodeKind(), request.getNodeId(), request.getProducerVersion());

				// Validated per entry only now that the default has been applied - "no collection" is an
				// error of the request as a whole, not of the entry in isolation.
				TagCreateRequest resolved = new TagCreateRequest().setName(entry.getName()).setCollection(collection);
				validator.validate(resolved);
				tags.add(tag);
			}

			// A writer that names itself withdraws only its own placements. A person's request carries no
			// node id and removes every placement of the named tags, which is what an untag means from a
			// human.
			AssetTagBulkResult result = dao().bulkTagAsset(asset, tags, withdraw, request.getNodeId());

			AssetTagBulkResponse response = new AssetTagBulkResponse();
			response.setTotal(tags.size());
			response.setApplied(result.applied().size());
			response.setWithdrawn(result.withdrawn());
			for (AssetTag tag : result.applied()) {
				response.add(modelBuilder.toResponse(tag));
			}
			lrc.send(response, 200);
		}, required);
	}

	/**
	 * Record who is attaching the tag.
	 *
	 * <p>
	 * A caller that says nothing is a person: {@code node_kind} stays {@code manual}, which is the schema's own default and the safe direction - a
	 * machine placement mislabelled as human is merely not filtered out of a view, while a human placement mislabelled as machine could be deleted by a
	 * node reconciling its own work.
	 * </p>
	 *
	 * <p>
	 * {@code attachedBy} is always the calling principal, worker or person. Authorship is {@code node_kind}; this is accountability, and a worker token
	 * still belongs to somebody.
	 * </p>
	 */
	private void applyProvenance(TagCreateRequest request, AssetTag tag, UUID userUuid,
		String defaultNodeKind, String defaultNodeId, String defaultProducerVersion) {
		String nodeKind = request.getNodeKind() == null ? defaultNodeKind : request.getNodeKind();
		String nodeId = request.getNodeId() == null ? defaultNodeId : request.getNodeId();
		String producerVersion = request.getProducerVersion() == null ? defaultProducerVersion : request.getProducerVersion();

		tag.setNodeKind(nodeKind == null ? AssetTag.MANUAL_NODE_KIND : nodeKind);
		tag.setNodeId(nodeId);
		tag.setProducerVersion(producerVersion == null ? "" : producerVersion);
		tag.setConfidence(request.getConfidence());
		tag.setAttachedBy(userUuid);
	}

	private void applyArea(AreaInfo area, AssetTag tag) {
		if (area == null) {
			return;
		}
		update(area::getHeight, tag::setAreaHeight);
		update(area::getWidth, tag::setAreaWidth);
		update(area::getStartX, tag::setAreaStartX);
		update(area::getStartY, tag::setAreaStartY);
		update(area::getFrom, tag::setTimeFrom);
		update(area::getTo, tag::setTimeTo);
	}

	public void untagAsset(LoomRoutingContext lrc, AssetId assetId, UUID tagUuid) {
		checkPerm(lrc, UNTAG_ASSET, () -> {

			Asset asset = daos().assetDao().loadById(assetId);
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetId);
			}
			Tag tag = dao().load(tagUuid);
			if (tag == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Tag not found " + tagUuid);
			}
			dao().untagAsset(tag, asset);
			lrc.sendNoContent();
		});
	}

	/**
	 * Detach one placement, leaving every other placement of the same tag on the asset alone.
	 *
	 * <p>
	 * The counterpart of {@code DELETE /assets/:uuid/tags/:tagUuid}, which removes the tag from the asset entirely. With a tag on three faces of a
	 * photo, that route clears the picture and this one clears a face.
	 * </p>
	 */
	public void removeTagPlacement(LoomRoutingContext lrc, AssetId assetId, UUID placementUuid) {
		checkPerm(lrc, UNTAG_ASSET, () -> {
			Asset asset = daos().assetDao().loadById(assetId);
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetId);
			}
			if (!dao().removePlacement(asset, placementUuid)) {
				// Also the answer when the placement belongs to a different asset: it does not exist as far
				// as this asset is concerned, and saying so would confirm a uuid the caller may not read.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Tag placement not found " + placementUuid);
			}
			lrc.sendNoContent();
		});
	}

	public void rateTag(LoomRoutingContext lrc, UUID tagUuid) {
		checkPerm(lrc, UPDATE_TAG, () -> {
			TagRatingRequest request = lrc.requestBody(TagRatingRequest.class);
			validator.validate(request);

			Tag tag = dao().load(tagUuid);
			if (tag == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Tag not found " + tagUuid);
			}

			UUID userUuid = lrc.userUuid();
			int rating = request.getRating();
			dao().storeUserRating(tagUuid, userUuid, rating);

			TagRatingResponse response = new TagRatingResponse().setRating(rating);
			lrc.send(response, 200);
		});
	}

	public void readTagRating(LoomRoutingContext lrc, UUID tagUuid) {
		checkPerm(lrc, READ_TAG, () -> {
			Tag tag = dao().load(tagUuid);
			if (tag == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Tag not found " + tagUuid);
			}

			UUID userUuid = lrc.userUuid();
			Integer rating = dao().readUserRating(tagUuid, userUuid);

			TagRatingResponse response = new TagRatingResponse().setRating(rating);
			lrc.send(response, 200);
		});
	}

	public void deleteTagRating(LoomRoutingContext lrc, UUID tagUuid) {
		checkPerm(lrc, UPDATE_TAG, () -> {
			Tag tag = dao().load(tagUuid);
			if (tag == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Tag not found " + tagUuid);
			}

			UUID userUuid = lrc.userUuid();
			dao().deleteUserRating(tagUuid, userUuid);
			lrc.sendNoContent();
		});
	}

}
