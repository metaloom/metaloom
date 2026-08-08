package io.metaloom.loom.db.model.cluster;

import java.time.Instant;
import java.util.UUID;

/**
 * One embedding's membership of a {@link Cluster}, joined with the geometry of the detection it came from.
 *
 * <p>
 * The row itself is {@code embedding_cluster}, which carries only the edge plus {@code confidence}/{@code origin}/{@code created}. The detection
 * fields are joined in because every consumer of a member list needs them together: the review UI draws a face crop, which takes the detection uuid
 * to address it and the bounding box to describe it. Returning the edge alone would force a second query per member.
 * </p>
 *
 * <p>
 * This is a read projection, not a stored entity - there is no {@code ClusterMemberDao} and no uuid of its own. The membership's identity is the
 * {@code (embeddingUuid, clusterUuid)} pair.
 * </p>
 */
public interface ClusterMember {

	/** Assigned by the clusterer. */
	String ORIGIN_AUTO = "AUTO";

	/** Moved here by a reviewer, overriding the clusterer. */
	String ORIGIN_MANUAL = "MANUAL";

	UUID getClusterUuid();

	ClusterMember setClusterUuid(UUID clusterUuid);

	UUID getEmbeddingUuid();

	ClusterMember setEmbeddingUuid(UUID embeddingUuid);

	/** Cosine similarity of this member to the cluster centroid at assignment time. */
	Float getConfidence();

	ClusterMember setConfidence(Float confidence);

	/** One of {@link #ORIGIN_AUTO}, {@link #ORIGIN_MANUAL}. */
	String getOrigin();

	ClusterMember setOrigin(String origin);

	Instant getCreated();

	ClusterMember setCreated(Instant created);

	/**
	 * The detection this member's embedding was extracted from, or {@code null} for an embedding that was written without one.
	 *
	 * <p>
	 * This is what addresses the face crop.
	 * </p>
	 */
	UUID getDetectionUuid();

	ClusterMember setDetectionUuid(UUID detectionUuid);

	UUID getAssetUuid();

	ClusterMember setAssetUuid(UUID assetUuid);

	/** Frame the detection was made in; 0 for a still image. */
	Integer getFrameNumber();

	ClusterMember setFrameNumber(Integer frameNumber);

	/** Bounding box as a 0-1 factor of the frame width. */
	Float getBboxX();

	ClusterMember setBboxX(Float bboxX);

	/** Bounding box as a 0-1 factor of the frame height. */
	Float getBboxY();

	ClusterMember setBboxY(Float bboxY);

	Float getBboxWidth();

	ClusterMember setBboxWidth(Float bboxWidth);

	Float getBboxHeight();

	ClusterMember setBboxHeight(Float bboxHeight);

}
