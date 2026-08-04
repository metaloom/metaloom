package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_TAG;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_TAG;
import static io.metaloom.loom.db.model.perm.Permission.READ_TAG;
import static io.metaloom.loom.db.model.perm.Permission.TAG_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UNTAG_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_TAG;

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
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
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

			AreaInfo area = request.getArea();
			if (area != null) {
				update(area::getHeight, tag::setAreaHeight);
				update(area::getWidth, tag::setAreaWidth);
				update(area::getStartX, tag::setAreaStartX);
				update(area::getStartY, tag::setAreaStartY);
				update(area::getFrom, tag::setTimeFrom);
				update(area::getTo, tag::setTimeTo);
			}

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
