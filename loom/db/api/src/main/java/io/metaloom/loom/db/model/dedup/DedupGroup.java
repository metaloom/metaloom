package io.metaloom.loom.db.model.dedup;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

/**
 * A candidate duplicate set discovered via fingerprint similarity: one KEEP member and one-or-more DUP members, holding a human confirm/deny decision.
 *
 * <p>
 * See spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md. The authoritative KEEP is the member with role {@link DedupGroupMember#ROLE_KEEP};
 * {@link #getKeepAssetUuid()} is a denormalised convenience pointer.
 * </p>
 *
 * <p>
 * <b>The two are not kept in sync.</b> {@link DedupGroupDao#updateStatus(UUID, String, UUID, UUID)} writes only the pointer, so a reviewer who
 * reassigns the KEEP leaves the member roles describing the machine's original choice. Readers must prefer {@link #getKeepAssetUuid()} when it is set
 * and fall back to the {@code KEEP} member otherwise.
 * </p>
 */
public interface DedupGroup extends CUDElement<DedupGroup> {

	/** Awaiting review. */
	String STATUS_PENDING = "PENDING";

	/** Confirmed by a reviewer; the apply node may act on it. */
	String STATUS_CONFIRMED = "CONFIRMED";

	/** Rejected by a reviewer; never act on it. */
	String STATUS_REJECTED = "REJECTED";

	String getAlgorithm();

	DedupGroup setAlgorithm(String algorithm);

	/** One of {@link #STATUS_PENDING}, {@link #STATUS_CONFIRMED}, {@link #STATUS_REJECTED}. */
	String getStatus();

	DedupGroup setStatus(String status);

	UUID getKeepAssetUuid();

	DedupGroup setKeepAssetUuid(UUID keepAssetUuid);

	/** Representative (minimum member) similarity score for the group. */
	Float getScore();

	DedupGroup setScore(Float score);
}
