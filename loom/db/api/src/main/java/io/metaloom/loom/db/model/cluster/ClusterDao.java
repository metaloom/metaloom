package io.metaloom.loom.db.model.cluster;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface ClusterDao extends CRUDDao<Cluster> {

	default Cluster createCluster(User user, String name, String type) {
		return createCluster(user.getUuid(), name, type);
	}

	Cluster createCluster(UUID userUuid, String name, String type);

	/**
	 * Create a cluster proposed by a producer rather than authored by a user: no creator, no name, {@link Cluster#STATUS_PENDING}.
	 *
	 * @param type         the cluster type, e.g. {@link Cluster#TYPE_FACE}
	 * @param nodeKind     the producing node kind, e.g. {@code facedetect}
	 * @param assetUuid    the asset the cluster was computed within
	 * @param clusterIndex deterministic ordinal within {@code (assetUuid, nodeKind)}
	 */
	Cluster createMachineCluster(String type, String nodeKind, UUID assetUuid, int clusterIndex);

	/**
	 * Insert the cluster, or update the existing row with the same {@code (asset_uuid, node_kind, cluster_index)}.
	 *
	 * <p>
	 * <b>Never overwrites {@code status}, {@code person_uuid}, {@code reviewed_at} or {@code reviewer_uuid}.</b> A node re-run must not undo a
	 * reviewer's decision, nor erase who made it - the proposal's geometry is the producer's to update, the verdict on it is not.
	 * </p>
	 *
	 * <p>
	 * {@code editor_uuid}/{@code edited} <em>are</em> overwritten, because they are the producer's own provenance. That is the distinction
	 * {@link Cluster#getReviewerUuid()} exists for.
	 * </p>
	 */
	Cluster upsertCluster(Cluster cluster);

	/**
	 * Delete this producer's {@link Cluster#STATUS_PENDING} clusters for the asset whose {@code cluster_index} is not in {@code keptIndices}.
	 *
	 * <p>
	 * A re-run that finds fewer subjects than the previous one would otherwise strand the surplus proposals forever. Only PENDING rows are removed:
	 * a cluster a human already decided on is a record of that decision and survives even if the producer no longer proposes it.
	 * </p>
	 *
	 * @return the number of clusters deleted
	 */
	int deleteStalePending(UUID assetUuid, String nodeKind, Collection<Integer> keptIndices);

	/** Link an embedding into a cluster as an {@link ClusterMember#ORIGIN_AUTO} member with no confidence. */
	void link(Cluster cluster, Embedding embedding);

	/**
	 * Link an embedding into a cluster, recording how it got there.
	 *
	 * <p>
	 * Idempotent: linking the same pair again updates {@code confidence}/{@code origin} rather than violating the primary key.
	 * </p>
	 */
	void link(UUID clusterUuid, UUID embeddingUuid, Float confidence, String origin);

	void unlink(Cluster cluster, Embedding embedding);

	/** Remove every membership of the cluster, leaving the cluster and the embeddings in place. */
	int unlinkAll(UUID clusterUuid);

	/** The cluster's members, joined with the geometry of the detections they came from, ordered by frame then bounding box. */
	List<ClusterMember> listMembers(UUID clusterUuid);

	long countMembers(UUID clusterUuid);

	/**
	 * Member counts for several clusters in one query.
	 *
	 * <p>
	 * The review queue renders a page of cards each showing "N faces"; asking per card is a query per card. Clusters with no members are absent from
	 * the result rather than mapped to zero.
	 * </p>
	 */
	Map<UUID, Long> countMembers(Collection<UUID> clusterUuids);

	/** Every cluster confirmed to be this person, newest first. */
	List<Cluster> findByPerson(UUID personUuid);

	/** Every cluster computed within this asset, in {@code cluster_index} order. */
	List<Cluster> listByAsset(UUID assetUuid);

	/**
	 * A page of clusters filtered by status and/or type, newest first.
	 *
	 * @param status one of the {@link Cluster} {@code STATUS_*} constants, or {@code null} for any
	 * @param type   the cluster type, or {@code null} for any
	 */
	Page<Cluster> loadPage(String status, String type, UUID fromId, int pageSize);

	/**
	 * Set the review verdict, and the person when confirming, in one transaction.
	 *
	 * <p>
	 * Also stamps {@code reviewed_at}/{@code reviewer_uuid}. That happens here rather than at the service layer so no caller can record a verdict
	 * without its author.
	 * </p>
	 *
	 * @param personUuid   the person to link, or {@code null} to leave the current pointer alone
	 * @param reviewerUuid the deciding user, recorded durably as {@link Cluster#getReviewerUuid()}
	 * @return the reloaded cluster
	 */
	Cluster updateStatus(UUID clusterUuid, String status, UUID personUuid, UUID reviewerUuid);

	/**
	 * Confirm the cluster, linking it to an existing person or creating one, atomically.
	 *
	 * <p>
	 * Both writes must land together: a created person with no cluster pointing at it is an orphan, and a CONFIRMED cluster with no person is a verdict
	 * with no subject.
	 * </p>
	 *
	 * <p>
	 * Stamps {@code reviewed_at}/{@code reviewer_uuid} in the same statement as the verdict, so a confirmed attribution can never be read back without
	 * its author.
	 * </p>
	 *
	 * @param personUuid   the person to link, or {@code null} to create one from {@code draft}
	 * @param draft        names for the person to create; ignored when {@code personUuid} is given
	 * @param reviewerUuid the deciding user; also the creator of a person this confirmation creates
	 * @return the reloaded cluster
	 */
	Cluster confirm(UUID clusterUuid, UUID personUuid, PersonDraft draft, UUID reviewerUuid);

	/**
	 * The names a confirmation may create a person with.
	 *
	 * @param alias     display name
	 * @param firstname given name
	 * @param lastname  family name
	 */
	record PersonDraft(String alias, String firstname, String lastname) {
	}

}
