package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqEmbeddingCluster.EMBEDDING_CLUSTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.cluster.ClusterMember;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.person.Person;
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
	 * {@code cluster.status} is a Postgres enum but the POJO holds a {@code String}, because {@code loom-db-api} cannot depend on the generated jOOQ
	 * types. This pins that the conversion survives a write/read round trip in both directions - the one thing the whole review model rests on.
	 */
	@Test
	public void testStatusRoundTrip() {
		User user = dummyUser();

		Cluster cluster = getDao().createCluster(user, "status_cluster", Cluster.TYPE_FACE);
		assertEquals(Cluster.STATUS_PENDING, cluster.getStatus(), "A freshly created cluster is a pending proposal");
		getDao().store(cluster);

		assertEquals(Cluster.STATUS_PENDING, getDao().load(cluster.getUuid()).getStatus(), "PENDING must survive the round trip");

		Cluster loaded = getDao().load(cluster.getUuid());
		loaded.setStatus(Cluster.STATUS_CONFIRMED);
		getDao().update(loaded);

		assertEquals(Cluster.STATUS_CONFIRMED, getDao().load(cluster.getUuid()).getStatus(), "CONFIRMED must survive an update");
	}

	/**
	 * Deleting a person nulls the pointer on every cluster confirmed to be them, and deletes none of them.
	 *
	 * <p>
	 * {@code ON DELETE SET NULL}, not CASCADE: the review record is evidence that a human looked at these faces and made a call. Removing the person
	 * removes the answer, not the question. A second, untouched cluster is asserted alongside so a delete that took everything would be visible.
	 * </p>
	 */
	@Test
	public void testDeletingPersonNullsClusterPersonUuid() {
		User user = dummyUser();

		Person person = personDao().createPerson(user, "Anna Meyer");
		personDao().store(person);

		Cluster confirmed = getDao().createCluster(user, "confirmed_cluster", Cluster.TYPE_FACE);
		confirmed.setStatus(Cluster.STATUS_CONFIRMED);
		confirmed.setPersonUuid(person.getUuid());
		getDao().store(confirmed);

		Cluster untouched = getDao().createCluster(user, "untouched_cluster", Cluster.TYPE_FACE);
		getDao().store(untouched);

		personDao().delete(person.getUuid());

		Cluster reloaded = getDao().load(confirmed.getUuid());
		assertNotNull(reloaded, "The cluster must survive deletion of the person");
		assertNull(reloaded.getPersonUuid(), "The person pointer must have been nulled");
		assertEquals(Cluster.STATUS_CONFIRMED, reloaded.getStatus(), "The verdict itself is unaffected by losing the person row");

		assertNotNull(getDao().load(untouched.getUuid()), "An unrelated cluster must not be touched");
	}

	/**
	 * Deleting an asset takes the clusters computed within it, since a per-asset cluster describes that asset and nothing else.
	 */
	@Test
	public void testDeletingAssetCascadesClusters() {
		Cluster cluster = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		getDao().store(cluster);
		assertNotNull(getDao().load(cluster.getUuid()));

		assetDao().delete(asset().getUuid());

		assertNull(getDao().load(cluster.getUuid()), "The cluster must have cascaded with its asset");
	}

	/**
	 * Re-running a producer over the same asset rewrites its own proposals instead of appending a second full set.
	 */
	@Test
	public void testUpsertIsIdempotent() {
		Cluster first = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		first.setScore(0.8f);
		getDao().upsertCluster(first);
		UUID firstUuid = first.getUuid();
		assertNotNull(firstUuid);

		Cluster second = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		second.setScore(0.9f);
		getDao().upsertCluster(second);

		assertEquals(firstUuid, second.getUuid(), "The same (asset, node_kind, cluster_index) must map to the same row");
		assertEquals(1, getDao().listByAsset(asset().getUuid()).size(), "A re-run must not append a second cluster");
		assertEquals(0.9f, getDao().load(firstUuid).getScore(), 0.0001f, "The producer's own payload is updated");
	}

	/**
	 * A node re-run must not undo a reviewer's decision.
	 *
	 * <p>
	 * The producer owns the geometry it computed; it does not own the verdict recorded against it since. Without the preserved-column set on the
	 * upsert, re-running face detection would silently reset every confirmed cluster to PENDING and drop its person.
	 * </p>
	 */
	@Test
	public void testUpsertDoesNotClobberConfirmedStatus() {
		User user = dummyUser();
		Person person = personDao().createPerson(user, "Anna Meyer");
		personDao().store(person);

		Cluster proposed = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		getDao().upsertCluster(proposed);

		getDao().confirm(proposed.getUuid(), person.getUuid(), null, user.getUuid());

		// The node runs again and re-proposes the same cluster, as a fresh PENDING with no person.
		Cluster reproposed = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		reproposed.setScore(0.77f);
		getDao().upsertCluster(reproposed);

		Cluster reloaded = getDao().load(proposed.getUuid());
		assertEquals(Cluster.STATUS_CONFIRMED, reloaded.getStatus(), "A re-run must not reset the reviewer's verdict");
		assertEquals(person.getUuid(), reloaded.getPersonUuid(), "A re-run must not drop the person link");
		assertEquals(0.77f, reloaded.getScore(), 0.0001f, "The producer's own payload is still updated");
	}

	/**
	 * Linking the same embedding twice refreshes the membership instead of violating the primary key.
	 */
	@Test
	public void testLinkIsIdempotent() {
		User user = dummyUser();
		Cluster cluster = getDao().createCluster(user, "link_cluster", Cluster.TYPE_FACE);
		getDao().store(cluster);

		Embedding embedding = embeddingDao().createEmbedding(user, asset(), VECTOR_DATA, "face");
		embedding.setNodeKind("facedetect");
		embedding.setSubjectIndex(1);
		embeddingDao().store(embedding);

		getDao().link(cluster.getUuid(), embedding.getUuid(), 0.5f, ClusterMember.ORIGIN_AUTO);
		getDao().link(cluster.getUuid(), embedding.getUuid(), 0.9f, ClusterMember.ORIGIN_MANUAL);

		assertEquals(1, getDao().countMembers(cluster.getUuid()), "Re-linking must not add a second membership row");
		ClusterMember member = getDao().listMembers(cluster.getUuid()).get(0);
		assertEquals(0.9f, member.getConfidence(), 0.0001f, "The confidence must have been refreshed");
		assertEquals(ClusterMember.ORIGIN_MANUAL, member.getOrigin(), "A reviewer's correction must overwrite the AUTO origin");
	}

	/**
	 * A member list carries the geometry of the detection behind each embedding, so the review UI can address a face crop without a query per member.
	 */
	@Test
	public void testListMembersJoinsDetectionGeometry() {
		User user = dummyUser();
		Cluster cluster = getDao().createCluster(user, "member_cluster", Cluster.TYPE_FACE);
		getDao().store(cluster);

		Detection detection = detectionDao().createDetection(user, "face");
		detection.setAssetUuid(asset().getUuid());
		detection.setNodeKind("facedetect");
		detection.setFrameNumber(7);
		detection.setDetectionIndex(0);
		detection.setBboxX(0.25f);
		detection.setBboxY(0.5f);
		detection.setBboxWidth(0.1f);
		detection.setBboxHeight(0.2f);
		detectionDao().upsertDetection(detection);

		Embedding embedding = embeddingDao().createEmbedding(user, asset(), VECTOR_DATA, "face");
		embedding.setNodeKind("facedetect");
		embedding.setSubjectIndex(0);
		embedding.setFrameNumber(7);
		embedding.setDetectionUuid(detection.getUuid());
		embeddingDao().store(embedding);

		getDao().link(cluster.getUuid(), embedding.getUuid(), 0.95f, ClusterMember.ORIGIN_AUTO);

		List<ClusterMember> members = getDao().listMembers(cluster.getUuid());
		assertEquals(1, members.size());
		ClusterMember member = members.get(0);
		assertEquals(embedding.getUuid(), member.getEmbeddingUuid());
		assertEquals(detection.getUuid(), member.getDetectionUuid(), "The detection must be joined through the embedding");
		assertEquals(asset().getUuid(), member.getAssetUuid());
		assertEquals(7, member.getFrameNumber());
		assertEquals(0.25f, member.getBboxX(), 0.0001f);
		assertEquals(0.2f, member.getBboxHeight(), 0.0001f);
	}

	/**
	 * Confirming without a person uuid creates one and links it, in a single transaction.
	 */
	@Test
	public void testConfirmCreatesPerson() {
		User user = dummyUser();
		Cluster cluster = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset().getUuid(), 0);
		getDao().upsertCluster(cluster);

		Cluster confirmed = getDao().confirm(cluster.getUuid(), null, new ClusterDao.PersonDraft("Anna Meyer", "Anna", "Meyer"), user.getUuid());

		assertEquals(Cluster.STATUS_CONFIRMED, confirmed.getStatus());
		assertNotNull(confirmed.getPersonUuid(), "A person must have been created and linked");

		Person created = personDao().load(confirmed.getPersonUuid());
		assertNotNull(created, "The created person must be readable");
		assertEquals("Anna Meyer", created.getAlias());

		assertEquals(1, getDao().findByPerson(confirmed.getPersonUuid()).size(), "The inverse lookup must find the cluster");
	}

	/**
	 * A shrinking re-run retires the proposals it no longer makes, but never a cluster a human has already decided on.
	 */
	@Test
	public void testDeleteStalePendingKeepsDecidedClusters() {
		User user = dummyUser();
		UUID assetUuid = asset().getUuid();

		Cluster kept = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", assetUuid, 0);
		getDao().upsertCluster(kept);
		Cluster stale = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", assetUuid, 1);
		getDao().upsertCluster(stale);
		Cluster decided = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", assetUuid, 2);
		getDao().upsertCluster(decided);
		getDao().updateStatus(decided.getUuid(), Cluster.STATUS_REJECTED, null, user.getUuid());

		int deleted = getDao().deleteStalePending(assetUuid, "facedetect", List.of(0));

		assertEquals(1, deleted, "Only the surplus PENDING proposal is retired");
		assertNotNull(getDao().load(kept.getUuid()), "A still-proposed cluster survives");
		assertNull(getDao().load(stale.getUuid()), "A no-longer-proposed PENDING cluster is retired");
		assertNotNull(getDao().load(decided.getUuid()), "A cluster a human decided on survives even when no longer proposed");
	}

	/**
	 * The exact shape the demo seed and the facedetect node both write: a machine cluster whose members are embeddings linked back to detections.
	 *
	 * <p>
	 * Worth pinning because the demo seed is only executed when a demo container boots - nothing in the test suite runs it - so a mistake there would
	 * surface as a container that fails to start rather than as a red test.
	 * </p>
	 */
	@Test
	public void testPendingClusterWithDetectionBackedMembers() {
		User user = dummyUser();
		UUID assetUuid = asset().getUuid();

		Cluster cluster = getDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", assetUuid, 0);
		cluster.setModel("inspireface-pikachu-r18");
		cluster.setProducerVersion("inspireface-pikachu-r18");
		cluster.setScore(0.93f);
		getDao().upsertCluster(cluster);

		for (int i = 0; i < 2; i++) {
			Detection detection = detectionDao().createDetection(user, "face");
			detection.setAssetUuid(assetUuid);
			detection.setNodeKind("facedetect");
			detection.setFrameNumber(0);
			detection.setDetectionIndex(i);
			detectionDao().upsertDetection(detection);

			Embedding embedding = embeddingDao().createEmbedding(user, asset(), VECTOR_DATA, "face");
			embedding.setNodeKind("facedetect");
			embedding.setModel("inspireface-pikachu-r18");
			embedding.setDetectionUuid(detection.getUuid());
			embedding.setSubjectIndex(i);
			embeddingDao().store(embedding);

			getDao().link(cluster.getUuid(), embedding.getUuid(), 0.95f, ClusterMember.ORIGIN_AUTO);
		}

		Cluster reloaded = getDao().load(cluster.getUuid());
		assertEquals(Cluster.STATUS_PENDING, reloaded.getStatus(), "a proposal starts pending");
		assertNull(reloaded.getName(), "a machine proposal has no name until a human supplies one");
		assertNull(reloaded.getCreatorUuid(), "a Cortex worker is not a user");
		assertEquals(2, getDao().countMembers(cluster.getUuid()));
		assertEquals(2, getDao().listMembers(cluster.getUuid()).stream().filter(m -> m.getDetectionUuid() != null).count(),
			"every member must resolve back to the detection it depicts, which is what addresses its crop");
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
