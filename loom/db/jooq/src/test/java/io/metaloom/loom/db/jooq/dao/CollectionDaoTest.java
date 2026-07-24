package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqCollectionCluster.COLLECTION_CLUSTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.user.User;

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

}
