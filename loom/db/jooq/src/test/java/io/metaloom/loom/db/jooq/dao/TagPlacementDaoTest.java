package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqTag.TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Placements: one tag, several places on one asset, each recording who put it there (V2.71).
 *
 * <p>
 * Until V2.71 <code>tag_asset</code> was keyed <code>(tag_uuid, asset_uuid)</code>, so a tag could be placed on an asset exactly once - which defeated
 * the region columns in the same table and made tagging two faces in one photo impossible, though that is precisely what face detection and clustering
 * produce. Nothing recorded whether a person or a pipeline attached a tag either, so the UI could not separate the two and a node had no way to prove a
 * tag was its own before withdrawing it.
 * </p>
 *
 * <p>
 * The cases that matter most here are the ones asserting what a node may <strong>not</strong> do to a placement it did not write.
 * </p>
 *
 * <p>
 * Split from {@link TagDaoTest} for a mundane reason: a test class much over twenty methods exhausts the pooled-database provider, and its last cases
 * then fail in {@code ProviderExtension.beforeEach} with no bearing on the code under test.
 * </p>
 */
public class TagPlacementDaoTest extends AbstractJooqTest {

	private TagDao getDao() {
		return tagDao();
	}

	/**
	 * 🔴 The reason V2.71 exists: one tag, several places on one asset. Tagging two faces in one photo - or one person at two timecodes - was impossible
	 * while the primary key was <code>(tag_uuid, asset_uuid)</code>.
	 */
	@Test
	public void testTagAssetPlacesTheSameTagTwice() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag person = getDao().createAssetTag(user, "anna", "people");
		getDao().resolveOrCreateAssetTag(person);

		placeAt(person, 10, 10);
		getDao().tagAsset(person, asset);
		UUID firstPlacement = person.getPlacementUuid();

		// The same tag, the same asset, a different face.
		placeAt(person, 400, 120);
		getDao().tagAsset(person, asset);
		UUID secondPlacement = person.getPlacementUuid();

		assertNotNull(firstPlacement, "A placement must report its own identity");
		assertNotEquals(firstPlacement, secondPlacement, "A different region is a different placement");

		List<AssetTag> placements = getDao().assetTags(asset).stream()
			.filter(t -> t.getUuid().equals(person.getUuid()))
			.toList();
		assertEquals(2, placements.size(), "Both faces must be tagged");
		assertEquals(1, context.ctx().fetchCount(TAG, TAG.NAME.eq("anna").and(TAG.COLLECTION.eq("people"))),
			"Two placements of one tag must still be one tag");
	}

	/** Who attached the tag travels on the placement, which is what lets a UI separate machine tags from curated ones. */
	@Test
	public void testTagAssetRecordsProvenance() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag machine = getDao().createAssetTag(user, "blurry", "quality");
		machine.setNodeKind("tag").setNodeId("tag:quality-tags").setProducerVersion("tag/1:abc").setConfidence(0.75f)
			.setAttachedBy(user.getUuid());
		getDao().resolveOrCreateAssetTag(machine);
		getDao().tagAsset(machine, asset);

		AssetTag stored = placement(asset, machine.getUuid());
		assertEquals("tag", stored.getNodeKind());
		assertEquals("tag:quality-tags", stored.getNodeId());
		assertEquals("tag/1:abc", stored.getProducerVersion());
		assertEquals(0.75f, stored.getConfidence(), 0.0001f);
		assertEquals(user.getUuid(), stored.getAttachedBy());
		assertNotNull(stored.getAttached(), "The placement must record when it happened");
		assertTrue(stored.isMachineWritten(), "A node-written placement must report itself as such");
	}

	/** An attachment that does not say who wrote it is a person's: the column defaults to 'manual' rather than to null. */
	@Test
	public void testTagAssetDefaultsToManual() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag typed = getDao().createAssetTag(user, "hand-typed", "quality");
		getDao().resolveOrCreateAssetTag(typed);
		getDao().tagAsset(typed, asset);

		AssetTag stored = placement(asset, typed.getUuid());
		assertEquals(AssetTag.MANUAL_NODE_KIND, stored.getNodeKind(), "An unattributed attachment is a human one");
		assertNull(stored.getNodeId(), "A person has no node id");
		assertFalse(stored.isMachineWritten());
	}

	/**
	 * 🔴 The first author keeps the row. A node attaching a tag a person already placed must not take the placement over: authorship would flip to the
	 * node, and the node's own reconciliation - which deletes by node id - would then delete somebody's curation on a later run.
	 */
	@Test
	public void testANodeDoesNotTakeOverAHumanPlacement() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag typed = getDao().createAssetTag(user, "shared-name", "quality");
		getDao().resolveOrCreateAssetTag(typed);
		getDao().tagAsset(typed, asset);
		UUID humanPlacement = typed.getPlacementUuid();

		AssetTag machine = getDao().createAssetTag(user, "shared-name", "quality");
		machine.setNodeKind("tag").setNodeId("tag:quality-tags").setProducerVersion("tag/1:abc").setConfidence(0.9f);
		getDao().resolveOrCreateAssetTag(machine);
		getDao().tagAsset(machine, asset);

		assertEquals(humanPlacement, machine.getPlacementUuid(),
			"The node must be told which placement carries its tag, even though it did not write it");

		AssetTag stored = placement(asset, typed.getUuid());
		assertEquals(AssetTag.MANUAL_NODE_KIND, stored.getNodeKind(), "Authorship must stay with the person");
		assertNull(stored.getNodeId(), "The node must not stamp its id onto a human placement");
		assertNull(stored.getConfidence(), "Nor its confidence");
		assertEquals(1, getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(typed.getUuid())).count(),
			"The tag is on the asset exactly once");
		assertEquals(0, getDao().assetTagsByNode(asset, "tag:quality-tags").size(),
			"...and the node owns nothing, so its reconciliation can delete nothing");
	}

	/** Removing one face's tag must leave the other faces tagged - the operation this migration exists for. */
	@Test
	public void testRemovePlacementRemovesOnlyThatOne() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag person = getDao().createAssetTag(user, "bob", "people");
		getDao().resolveOrCreateAssetTag(person);
		placeAt(person, 10, 10);
		getDao().tagAsset(person, asset);
		UUID first = person.getPlacementUuid();
		placeAt(person, 400, 120);
		getDao().tagAsset(person, asset);
		UUID second = person.getPlacementUuid();

		assertTrue(getDao().removePlacement(asset, first), "The named placement must be removed");

		List<AssetTag> left = getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(person.getUuid())).toList();
		assertEquals(1, left.size(), "The other face must still be tagged");
		assertEquals(second, left.get(0).getPlacementUuid());
		assertNotNull(getDao().load(person.getUuid()), "Removing a placement must not delete the tag");
	}

	/** A placement uuid alone must not reach across assets. */
	@Test
	public void testRemovePlacementIsScopedByAsset() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag tag = getDao().createAssetTag(user, "scoped", "quality");
		getDao().resolveOrCreateAssetTag(tag);
		getDao().tagAsset(tag, asset);
		UUID placement = tag.getPlacementUuid();

		Asset other = otherAsset();
		assertFalse(getDao().removePlacement(other, placement), "A placement of another asset must not be removable");
		assertEquals(1, getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(tag.getUuid())).count(),
			"The placement must survive the attempt");
	}

	/** Untagging removes every placement of that tag: "take this tag off this picture" means all of it. */
	@Test
	public void testUntagAssetRemovesEveryPlacement() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag person = getDao().createAssetTag(user, "carol", "people");
		getDao().resolveOrCreateAssetTag(person);
		placeAt(person, 10, 10);
		getDao().tagAsset(person, asset);
		placeAt(person, 300, 40);
		getDao().tagAsset(person, asset);

		getDao().untagAsset(person, asset);

		assertEquals(0, getDao().assetTags(asset).stream().filter(t -> t.getUuid().equals(person.getUuid())).count(),
			"Both placements must be gone");
		assertNotNull(getDao().load(person.getUuid()), "The tag itself survives");
	}

	/** "Which tags on this asset are mine" is now a question the database answers. */
	@Test
	public void testAssetTagsByNode() {
		User user = dummyUser();
		Asset asset = asset();

		AssetTag mine = getDao().createAssetTag(user, "mine", "quality");
		mine.setNodeKind("tag").setNodeId("tag:node-a");
		getDao().resolveOrCreateAssetTag(mine);
		getDao().tagAsset(mine, asset);

		AssetTag theirs = getDao().createAssetTag(user, "theirs", "quality");
		theirs.setNodeKind("tag").setNodeId("tag:node-b");
		getDao().resolveOrCreateAssetTag(theirs);
		getDao().tagAsset(theirs, asset);

		AssetTag typed = getDao().createAssetTag(user, "typed", "quality");
		getDao().resolveOrCreateAssetTag(typed);
		getDao().tagAsset(typed, asset);

		List<AssetTag> owned = getDao().assetTagsByNode(asset, "tag:node-a");
		assertEquals(1, owned.size(), "Only this node's placements");
		assertEquals("mine", owned.get(0).getName());
	}

	/** Put the tag's box at the given corner. The region setters return {@code Tag}, so they do not chain. */
	private void placeAt(AssetTag tag, int x, int y) {
		tag.setAreaStartX(x);
		tag.setAreaStartY(y);
		tag.setAreaWidth(50);
		tag.setAreaHeight(50);
	}

	/** A second asset, for the cases where "the same uuid on a different asset" is the thing under test. */
	private Asset otherAsset() {
		Asset other = assetDao().createAsset(dummyUser().getUuid(),
			SHA512.fromString(SHA512SUM.toString().substring(0, 124) + "beef"),
			IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(other);
		return other;
	}

	/** The placement carrying the given tag on the given asset; fails the test when there is none. */
	private AssetTag placement(Asset asset, UUID tagUuid) {
		return getDao().assetTags(asset).stream()
			.filter(t -> t.getUuid().equals(tagUuid))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The tag is not attached to the asset"));
	}
}
