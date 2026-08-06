package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqEmbeddingCluster.EMBEDDING_CLUSTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.user.User;

public class ClusterDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ClusterDao, Cluster> {

	@Override
	public ClusterDao getDao() {
		return clusterDao();
	}

	@Override
	public Cluster createElement(User user, int i) {
		return getDao().createCluster(user, "cluster_name_" + i, "PERSON");
	}

	@Override
	public void assertCreate(Cluster createdElement) {
		assertEquals("cluster_name_0", createdElement.getName());
		assertEquals("PERSON", createdElement.getType());
	}

	@Override
	public void assertUpdate(Cluster updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Cluster cluster) {
		cluster.setName("new_name");
	}

	/**
	 * Deleting a cluster cascades its {@code embedding_cluster} membership rows (V2.51); the embeddings themselves survive.
	 */
	@Test
	public void testDeleteCascadesEmbeddingClusterLinks() {
		User user = dummyUser();

		Cluster cluster = getDao().createCluster(user, "cascade_cluster", "PERSON");
		getDao().store(cluster);

		Embedding embedding1 = embeddingDao().createEmbedding(user, asset(), VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1.name());
		embedding1.setNodeKind("facedetect");
		embedding1.setSubjectIndex(1);
		embeddingDao().store(embedding1);
		Embedding embedding2 = embeddingDao().createEmbedding(user, asset(), VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1.name());
		embedding2.setNodeKind("facedetect");
		embedding2.setSubjectIndex(2);
		embeddingDao().store(embedding2);

		getDao().link(cluster, embedding1);
		getDao().link(cluster, embedding2);

		assertEquals(2, context.ctx().fetchCount(EMBEDDING_CLUSTER, EMBEDDING_CLUSTER.CLUSTER_UUID.eq(cluster.getUuid())),
			"Both embedding_cluster links should exist before deletion");

		getDao().delete(cluster.getUuid());

		assertNull(getDao().load(cluster.getUuid()), "The cluster is gone");
		assertEquals(0, context.ctx().fetchCount(EMBEDDING_CLUSTER, EMBEDDING_CLUSTER.CLUSTER_UUID.eq(cluster.getUuid())),
			"The embedding_cluster links must have cascaded");
		assertNotNull(embeddingDao().load(embedding1.getUuid()), "The first embedding must survive deletion of the cluster");
		assertNotNull(embeddingDao().load(embedding2.getUuid()), "The second embedding must survive deletion of the cluster");
	}

}
