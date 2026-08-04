package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTag.ANNOTATION_TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTag.TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTagAsset.TAG_ASSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class TagDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<TagDao, Tag> {

	@Override
	public TagDao getDao() {
		return tagDao();
	}

	@Override
	public Tag createElement(User user, int i) {
		return getDao().createTag(user, "tag_" + i, "colors");
	}

	@Override
	public void assertCreate(Tag createdElement) {
		assertEquals("tag_0", createdElement.getName());
		assertEquals("colors", createdElement.getCollection());
	}

	@Override
	public void assertUpdate(Tag updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Tag tag) {
		tag.setName("new_name");
	}

	/**
	 * Pins the defect {@code resolveOrCreateAssetTag} was written for: {@link TagDao#store} is an unconditional INSERT, so the second asset to receive
	 * a tag name hits {@code UNIQUE (name, collection)}. Nothing may go back to storing a tag it means to attach.
	 */
	@Test
	public void testStoreCannotShareATagName() {
		User user = dummyUser();

		getDao().store(getDao().createAssetTag(user, "unshareable", "quality"));

		assertThrows(DataAccessException.class,
			() -> getDao().store(getDao().createAssetTag(user, "unshareable", "quality")),
			"A plain insert cannot reuse an existing tag name");
	}

	/**
	 * The reason {@code resolveOrCreateAssetTag} exists: {@code tag} is {@code UNIQUE (name, collection)}, so tagging a second asset with a name that
	 * is already in use must resolve the existing row rather than insert a second one.
	 */
	@Test
	public void testResolveOrCreateReusesTheExistingTag() {
		User user = dummyUser();

		AssetTag first = getDao().createAssetTag(user, "shared_tag", "quality");
		first.setMeta(new JsonObject().put("origin", "human"));
		UUID firstUuid = getDao().resolveOrCreateAssetTag(first);
		assertNotNull(firstUuid, "The first write must create the tag");

		AssetTag second = getDao().createAssetTag(user, "shared_tag", "quality");
		UUID secondUuid = getDao().resolveOrCreateAssetTag(second);

		assertEquals(firstUuid, secondUuid, "The second write must resolve the existing tag");
		assertEquals(1, context.ctx().fetchCount(TAG, TAG.NAME.eq("shared_tag").and(TAG.COLLECTION.eq("quality"))),
			"Only one tag row may exist for one (name, collection)");
	}

	/**
	 * A name that is free in one collection and taken in another is a different tag - the natural key is the pair, not the name.
	 */
	@Test
	public void testResolveOrCreateIsScopedByCollection() {
		User user = dummyUser();

		UUID inColors = getDao().resolveOrCreateAssetTag(getDao().createAssetTag(user, "amber", "colors"));
		UUID inMoods = getDao().resolveOrCreateAssetTag(getDao().createAssetTag(user, "amber", "moods"));

		assertNotEquals(inColors, inMoods, "The same name in a different collection is a different tag");
	}

	/**
	 * Resolving must not rewrite a tag someone else curated: an existing tag keeps its meta, its colour and its original creation audit, and only the
	 * values the resolving call actually supplies are written.
	 */
	@Test
	public void testResolveOrCreateDoesNotOverwriteTheExistingTag() {
		User user = dummyUser();

		AssetTag original = getDao().createAssetTag(user, "curated", "quality");
		original.setMeta(new JsonObject().put("note", "hand written"));
		original.setColor("ff0000");
		getDao().resolveOrCreateAssetTag(original);

		Tag stored = getDao().load(original.getUuid());
		Instant created = stored.getCreated();

		// A second, bare write - the shape a worker produces: a name and a collection, nothing else.
		AssetTag bare = getDao().createAssetTag(adminUser(), "curated", "quality");
		getDao().resolveOrCreateAssetTag(bare);

		Tag after = getDao().load(original.getUuid());
		assertEquals("hand written", after.getMeta().getString("note"), "The existing meta must survive");
		assertEquals("ff0000", after.getColor(), "The existing colour must survive");
		assertEquals(user.getUuid(), after.getCreatorUuid(), "The original creator must survive");
		assertEquals(created, after.getCreated(), "The original creation timestamp must survive");

		// ...and the resolved pojo reports what the tag *is*, not what the bare call proposed.
		assertEquals("hand written", bare.getMeta().getString("note"), "The resolved tag must carry the persisted meta");
	}

	/**
	 * Re-running a pipeline over an asset it has already tagged is the normal case. {@code tag_asset} is keyed {@code (tag_uuid, asset_uuid)}, so the
	 * join write has to be an upsert; the region is what it updates.
	 */
	@Test
	public void testTagAssetIsIdempotent() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag tag = getDao().createAssetTag(user, "repeatable", "quality");
		getDao().resolveOrCreateAssetTag(tag);
		getDao().tagAsset(tag, asset);
		getDao().tagAsset(tag, asset);

		assertEquals(1, context.ctx().fetchCount(TAG_ASSET,
			TAG_ASSET.TAG_UUID.eq(tag.getUuid()).and(TAG_ASSET.ASSET_UUID.eq(asset.getUuid()))),
			"Tagging the same asset twice must leave one join row");
		assertEquals(1, getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(tag.getUuid())).count(),
			"The tag must be listed once on the asset");

		// The second write updates the region rather than being ignored.
		tag.setAreaStartX(10);
		tag.setAreaStartY(20);
		tag.setAreaWidth(30);
		tag.setAreaHeight(40);
		getDao().tagAsset(tag, asset);

		AssetTag reloaded = getDao().assetTags(asset).stream()
			.filter(t -> t.getUuid().equals(tag.getUuid()))
			.findFirst()
			.orElseThrow();
		assertEquals(10, reloaded.getAreaStartX(), "The region must be updated by the second write");
		assertEquals(40, reloaded.getAreaHeight(), "The region must be updated by the second write");
	}

	/**
	 * Deleting a tag that is linked to an annotation cascades the {@code annotation_tag} join row (V2.16); the annotation survives.
	 */
	@Test
	public void testDeleteCascadesAnnotationTagLink() {
		User user = dummyUser();

		Annotation annotation = annotationDao().createAnnotation(user, asset(), "annotation", AnnotationType.FEEDBACK);
		annotationDao().store(annotation);

		Tag tag = getDao().createTag(user, "linked_tag", "colors");
		getDao().store(tag);
		getDao().tagAnnotation(tag, annotation);

		assertEquals(1, context.ctx().fetchCount(ANNOTATION_TAG, ANNOTATION_TAG.TAG_UUID.eq(tag.getUuid())),
			"The annotation_tag link should exist before deletion");

		getDao().delete(tag.getUuid());

		assertNull(getDao().load(tag.getUuid()), "The tag is gone");
		assertEquals(0, context.ctx().fetchCount(ANNOTATION_TAG, ANNOTATION_TAG.TAG_UUID.eq(tag.getUuid())),
			"The annotation_tag link must have cascaded");
		assertNotNull(annotationDao().load(annotation.getUuid()), "The annotation must survive deletion of the tag");
	}

}
