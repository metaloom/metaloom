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
import java.util.List;
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
import io.metaloom.loom.db.model.tag.TagDao.AssetTagBulkResult;
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

		// Correcting the *extent* of a box updates the same placement: areaWidth/areaHeight sit outside
		// the placement key precisely so a resized box does not become a second tag on the picture. Moving
		// the box to a different corner is a different placement - see TagPlacementDaoTest.
		AssetTag boxed = getDao().createAssetTag(user, "repeatable", "quality");
		boxed.setAreaStartX(10);
		boxed.setAreaStartY(20);
		boxed.setAreaWidth(30);
		boxed.setAreaHeight(40);
		getDao().resolveOrCreateAssetTag(boxed);
		getDao().tagAsset(boxed, asset);

		boxed.setAreaWidth(60);
		boxed.setAreaHeight(80);
		getDao().tagAsset(boxed, asset);

		List<AssetTag> boxes = getDao().assetTags(asset).stream()
			.filter(t -> t.getUuid().equals(tag.getUuid()) && t.getAreaStartX() != null)
			.toList();
		assertEquals(1, boxes.size(), "Resizing a box must update the placement rather than add one");
		assertEquals(60, boxes.get(0).getAreaWidth(), "The box extent must be updated by the second write");
		assertEquals(80, boxes.get(0).getAreaHeight(), "The box extent must be updated by the second write");
	}

	/**
	 * The bulk write is the single-tag write applied to a set: every tag is still resolved on its natural key, so a name already in the catalog is
	 * attached rather than duplicated.
	 */
	@Test
	public void testBulkTagAssetAppliesTheWholeSet() {
		User user = dummyUser();
		Asset asset = asset();

		// One of the three already exists, written by somebody else.
		AssetTag existing = getDao().createAssetTag(user, "bulk_existing", "quality");
		UUID existingUuid = getDao().resolveOrCreateAssetTag(existing);

		List<AssetTag> tags = List.of(
			getDao().createAssetTag(user, "bulk_existing", "quality"),
			getDao().createAssetTag(user, "bulk_one", "quality"),
			getDao().createAssetTag(user, "bulk_two", "quality"));

		AssetTagBulkResult result = getDao().bulkTagAsset(asset, tags, null);

		assertEquals(3, result.applied().size(), "Every tag of the set must be applied");
		assertEquals(0, result.withdrawn(), "Nothing was named for withdrawal");
		assertEquals(existingUuid, tags.get(0).getUuid(), "An existing name must resolve to the existing tag");
		assertEquals(1, context.ctx().fetchCount(TAG, TAG.NAME.eq("bulk_existing").and(TAG.COLLECTION.eq("quality"))),
			"The shared tag must not have been duplicated");
		assertEquals(3, getDao().assetTags(asset).stream().filter(t -> t.getName().startsWith("bulk_")).count(),
			"All three tags must be attached to the asset");

		// Re-applying the same set changes nothing - the normal case for a pipeline running twice.
		getDao().bulkTagAsset(asset, tags, null);
		assertEquals(3, getDao().assetTags(asset).stream().filter(t -> t.getName().startsWith("bulk_")).count(),
			"Re-applying the same set must not duplicate the attachments");
	}

	/**
	 * 🔴 Withdrawal removes exactly what the caller names and nothing else.
	 *
	 * <p>
	 * The tempting semantic for a bulk write - "these are the tags now, delete the rest" - is what this test forbids. A writer may only remove what it
	 * can point at; deleting by omission would let one writer destroy another's curation.
	 * </p>
	 */
	@Test
	public void testBulkTagAssetWithdrawsOnlyWhatItNames() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag mine = getDao().createAssetTag(user, "withdraw_mine", "quality");
		AssetTag doomed = getDao().createAssetTag(user, "withdraw_doomed", "quality");
		AssetTag someoneElses = getDao().createAssetTag(user, "withdraw_theirs", "curated");
		getDao().bulkTagAsset(asset, List.of(mine, doomed, someoneElses), null);

		AssetTagBulkResult result = getDao().bulkTagAsset(asset, List.of(mine), List.of(doomed.getUuid()));

		assertEquals(1, result.withdrawn(), "Exactly the one named attachment must be removed");
		assertEquals(0, context.ctx().fetchCount(TAG_ASSET,
			TAG_ASSET.TAG_UUID.eq(doomed.getUuid()).and(TAG_ASSET.ASSET_UUID.eq(asset.getUuid()))),
			"The withdrawn tag must no longer be attached");
		assertNotNull(getDao().load(doomed.getUuid()), "Withdrawing detaches the tag; it must not delete it");
		assertEquals(1, getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(someoneElses.getUuid())).count(),
			"A tag the call did not name must survive, even though it was absent from the applied set");
	}

	/** Naming a tag which is not attached is not an error - a writer reconciling its own list cannot know what a concurrent run already removed. */
	@Test
	public void testBulkWithdrawIgnoresATagThatIsNotAttached() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag unattached = getDao().createAssetTag(user, "never_attached", "quality");
		getDao().resolveOrCreateAssetTag(unattached);

		AssetTagBulkResult result = getDao().bulkTagAsset(asset, List.of(), List.of(unattached.getUuid()));

		assertEquals(0, result.withdrawn(), "Nothing was attached, so nothing can be withdrawn");
		assertNotNull(getDao().load(unattached.getUuid()), "The tag itself must survive");
	}

	/**
	 * The point of the transaction: a set is applied whole or not at all. Without it an asset could be left carrying the tags that happened to be
	 * written before the failing one, which is precisely the half-tagged state a reconciling writer cannot recover from.
	 */
	@Test
	public void testBulkTagAssetRollsBackTheWholeSet() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag good = getDao().createAssetTag(user, "rollback_good", "quality");
		// name is NOT NULL, so this one cannot be inserted.
		AssetTag broken = getDao().createAssetTag(user, null, "quality");

		assertThrows(DataAccessException.class,
			() -> getDao().bulkTagAsset(asset, List.of(good, broken), null),
			"A set with an unwritable tag must fail");

		assertEquals(0, context.ctx().fetchCount(TAG, TAG.NAME.eq("rollback_good")),
			"The tag written before the failure must have been rolled back");
		assertEquals(0, getDao().assetTags(asset).stream().filter(t -> "rollback_good".equals(t.getName())).count(),
			"The asset must carry none of the set");
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
