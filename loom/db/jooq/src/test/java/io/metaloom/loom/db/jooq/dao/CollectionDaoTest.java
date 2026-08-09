package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqCollectionCluster.COLLECTION_CLUSTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

public class CollectionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<CollectionDao, Collection> {

	@Override
	public CollectionDao getDao() {
		return collectionDao();
	}

	@Override
	public Collection createElement(User user, int i) {
		return getDao().createCollection(user, "name_" + i);
	}

	@Override
	public void assertCreate(Collection createdElement) {
		assertEquals("name_" + 0, createdElement.getName());
	}

	@Override
	public void assertUpdate(Collection updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Collection collection) {
		collection.setName("new_name");
	}

	/**
	 * Deleting a collection cascades its {@code collection_cluster} link rows (V2.12); the linked cluster survives.
	 */
	@Test
	public void testDeleteCascadesCollectionClusterLink() {
		User user = dummyUser();

		Collection collection = getDao().createCollection(user, "cascade_collection");
		getDao().store(collection);

		Cluster cluster = clusterDao().createCluster(user, "collection_cluster_member", "PERSON");
		clusterDao().store(cluster);

		// collection_cluster has no DAO writer, so the link is inserted directly.
		context.ctx().insertInto(COLLECTION_CLUSTER, COLLECTION_CLUSTER.COLLECTION_UUID, COLLECTION_CLUSTER.CLUSTER_UUID)
			.values(collection.getUuid(), cluster.getUuid())
			.execute();

		assertEquals(1, context.ctx().fetchCount(COLLECTION_CLUSTER, COLLECTION_CLUSTER.COLLECTION_UUID.eq(collection.getUuid())),
			"The collection_cluster link should exist before deletion");

		getDao().delete(collection.getUuid());

		assertNull(getDao().load(collection.getUuid()), "The collection is gone");
		assertEquals(0, context.ctx().fetchCount(COLLECTION_CLUSTER, COLLECTION_CLUSTER.COLLECTION_UUID.eq(collection.getUuid())),
			"The collection_cluster link must have cascaded");
		assertNotNull(clusterDao().load(cluster.getUuid()), "The cluster must survive deletion of the collection");
	}

	/**
	 * Linking the same asset twice is a no-op, not a duplicate-key error.
	 *
	 * <p>
	 * {@code collection_asset} is keyed on {@code (collection_uuid, asset_uuid)}, so the plain insert this DAO used to issue threw on the second call
	 * - a 500 for every already-curated asset a pipeline re-visited.
	 * </p>
	 */
	@Test
	public void testLinkAssetIsIdempotent() {
		User user = dummyUser();
		Collection collection = getDao().createCollection(user, "idempotent_link");
		getDao().store(collection);
		Asset asset = dummyAsset(user);

		getDao().linkAsset(collection.getUuid(), asset.getUuid());
		getDao().linkAsset(collection.getUuid(), asset.getUuid());

		assertTrue(getDao().containsAsset(collection.getUuid(), asset.getUuid()), "The asset should be a member");
		assertEquals(1, getDao().countAssets(collection.getUuid()), "The asset should be a member exactly once");
	}

	@Test
	public void testUnlinkAsset() {
		User user = dummyUser();
		Collection collection = getDao().createCollection(user, "unlink");
		getDao().store(collection);
		Asset asset = dummyAsset(user);

		getDao().linkAsset(collection.getUuid(), asset.getUuid());
		getDao().unlinkAsset(collection.getUuid(), asset.getUuid());

		assertFalse(getDao().containsAsset(collection.getUuid(), asset.getUuid()), "The asset should no longer be a member");
		assertEquals(0, getDao().countAssets(collection.getUuid()), "The collection should be empty");
	}

	@Test
	public void testLoadPageByAsset() {
		User user = dummyUser();
		Asset asset = dummyAsset(user);

		Collection first = getDao().createCollection(user, "page_by_asset_a");
		getDao().store(first);
		Collection second = getDao().createCollection(user, "page_by_asset_b");
		getDao().store(second);
		getDao().linkAsset(first.getUuid(), asset.getUuid());
		getDao().linkAsset(second.getUuid(), asset.getUuid());

		Page<Collection> page = getDao().loadPageByAsset(asset.getUuid(), null, 25);
		assertEquals(2, page.totalCount(), "Both collections should be reachable from the asset");
	}

	/**
	 * Two collections holding the same asset: deleting one must leave the other's membership intact. A cascade that is one join column too wide would
	 * silently empty an unrelated collection.
	 */
	@Test
	public void testDeleteCascadesOnlyItsOwnMemberships() {
		User user = dummyUser();
		Asset asset = dummyAsset(user);

		Collection victim = getDao().createCollection(user, "cascade_victim");
		getDao().store(victim);
		Collection bystander = getDao().createCollection(user, "cascade_bystander");
		getDao().store(bystander);
		getDao().linkAsset(victim.getUuid(), asset.getUuid());
		getDao().linkAsset(bystander.getUuid(), asset.getUuid());

		getDao().delete(victim.getUuid());

		assertEquals(0, getDao().countAssets(victim.getUuid()), "The deleted collection's memberships must have cascaded");
		assertEquals(1, getDao().countAssets(bystander.getUuid()), "The other collection must keep its member");
		assertNotNull(assetDao().load(asset.getUuid()), "The asset itself must survive");
	}

	private int assetCounter = 0;

	/**
	 * A stored asset with a hash unique within this test class, so several memberships can be exercised without PK collisions.
	 */
	private Asset dummyAsset(User user) {
		int i = assetCounter++;
		String base = SHA512SUM.toString().substring(0, 124);
		SHA512 sha = SHA512.fromString(base + String.format("%04x", i));
		Asset asset = assetDao().createAsset(user, sha, IMAGE_MIMETYPE, "membership-" + i + ".png", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

}
