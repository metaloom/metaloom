package io.metaloom.loom.db.model.cluster;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.review.ReviewStatus;

/**
 * A group of embeddings that a producer believes belong to one subject, carrying a human confirm/reject decision.
 *
 * <p>
 * A {@code type='face'} cluster is proposed per asset by the facedetect node with {@link #STATUS_PENDING}, reviewed by a human, and on confirmation
 * linked to a {@code person} via {@link #getPersonUuid()}. The review model mirrors {@code dedup_group}: machine proposes, human decides.
 * </p>
 *
 * <p>
 * {@link #getStatus()} is a {@code String} rather than an enum even though the column is a Postgres enum, because {@code loom-db-api} cannot depend on
 * the generated jOOQ types. The conversion happens in the DAO. Use the {@code STATUS_*} constants.
 * </p>
 */
public interface Cluster extends CUDElement<Cluster> {

	/** Proposed by a producer and awaiting human review. The default for a machine-written cluster. */
	String STATUS_PENDING = ReviewStatus.PENDING;

	/** Confirmed by a reviewer; {@link #getPersonUuid()} names who it is. */
	String STATUS_CONFIRMED = ReviewStatus.CONFIRMED;

	/** Rejected by a reviewer; not a real subject. */
	String STATUS_REJECTED = ReviewStatus.REJECTED;

	/** The {@code cluster.type} the face workflow proposes and reviews. */
	String TYPE_FACE = "face";

	/**
	 * Human-supplied label, or {@code null} for a machine proposal nobody has named yet.
	 *
	 * <p>
	 * Unique per {@code (type, name)} when set. It used to be globally unique across every cluster type, which forbade two people of the same name.
	 * </p>
	 */
	String getName();

	Cluster setName(String name);

	String getType();

	Cluster setType(String type);

	/** One of {@link #STATUS_PENDING}, {@link #STATUS_CONFIRMED}, {@link #STATUS_REJECTED}. */
	String getStatus();

	Cluster setStatus(String status);

	/**
	 * The person a reviewer confirmed this cluster to be, or {@code null} while it is unreviewed or rejected.
	 *
	 * <p>
	 * Nulled rather than cascaded when the person is deleted: the review record outlives the person row.
	 * </p>
	 */
	UUID getPersonUuid();

	Cluster setPersonUuid(UUID personUuid);

	/**
	 * When a human recorded the verdict, or {@code null} while nobody has.
	 *
	 * <p>
	 * Deliberately not {@code edited}: the producing node touches that on every re-run, so reading it as a review timestamp would report a review that
	 * never happened (V2.88).
	 * </p>
	 */
	Instant getReviewedAt();

	Cluster setReviewedAt(Instant reviewedAt);

	/**
	 * The user who recorded the verdict, or {@code null} while nobody has.
	 *
	 * <p>
	 * Separate from {@code editorUuid}, which is machine-written provenance and is NULL for a node-written row (V2.47). Attributing a face to a named
	 * person is a biometric decision, and it keeps its author across every later run of the producing node.
	 * </p>
	 */
	UUID getReviewerUuid();

	Cluster setReviewerUuid(UUID reviewerUuid);

	/**
	 * The asset this cluster was computed within, or {@code null} for a human-authored cluster.
	 *
	 * <p>
	 * Nullable on purpose: a library-wide cluster (cross-asset identity) lives in this same table, distinguished by {@link #getNodeKind()}.
	 * </p>
	 */
	UUID getAssetUuid();

	Cluster setAssetUuid(UUID assetUuid);

	/**
	 * Ordinal of this cluster within {@code (assetUuid, nodeKind)}.
	 *
	 * <p>
	 * Assigned deterministically by the producer so a re-run upserts rather than appends. It is content-derived, not input-order-derived, because the
	 * face scanner returns faces sharpest-first and that order is not stable.
	 * </p>
	 */
	Integer getClusterIndex();

	Cluster setClusterIndex(Integer clusterIndex);

	/** Cohesion: mean cosine similarity of the members to {@link #getCentroid()}. {@code null} for a singleton. */
	Float getScore();

	Cluster setScore(Float score);

	/**
	 * Unit-normalised mean of the member vectors.
	 *
	 * <p>
	 * Only comparable against embeddings sharing this row's {@link #getModel()} and {@link #getDimensions()} - a centroid from another model is not in
	 * the same space.
	 * </p>
	 */
	Float[] getCentroid();

	Cluster setCentroid(Float[] centroid);

	/** Model the member embeddings were produced by; qualifies {@link #getCentroid()}. */
	String getModel();

	Cluster setModel(String model);

	/** Length of {@link #getCentroid()}; qualifies it together with {@link #getModel()}. */
	Integer getDimensions();

	Cluster setDimensions(Integer dimensions);

	/** Producing node kind, e.g. {@code facedetect}. {@code null} for a human-authored cluster. */
	String getNodeKind();

	Cluster setNodeKind(String nodeKind);

	String getNodeId();

	Cluster setNodeId(String nodeId);

	/** Version of the producer and its model, so a model change can retire stale proposals. */
	String getProducerVersion();

	Cluster setProducerVersion(String producerVersion);

	UUID getRunUuid();

	Cluster setRunUuid(UUID runUuid);

	UUID getTaskUuid();

	Cluster setTaskUuid(UUID taskUuid);

}
