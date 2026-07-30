package io.metaloom.loom.db.model.dedup;

import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * One member of a {@link DedupGroup}: an asset playing either the KEEP or a DUP role, with the similarity score to the KEEP and size/completeness
 * snapshots taken at discovery time.
 */
public interface DedupGroupMember extends Element<DedupGroupMember> {

	/** The asset to keep. Exactly one KEEP per group. */
	String ROLE_KEEP = "KEEP";

	/** A candidate duplicate the apply node may move once the group is confirmed. */
	String ROLE_DUP = "DUP";

	UUID getGroupUuid();

	DedupGroupMember setGroupUuid(UUID groupUuid);

	UUID getAssetUuid();

	DedupGroupMember setAssetUuid(UUID assetUuid);

	/** One of {@link #ROLE_KEEP}, {@link #ROLE_DUP}. */
	String getRole();

	DedupGroupMember setRole(String role);

	/** Similarity of this member to the KEEP. */
	Float getScore();

	DedupGroupMember setScore(Float score);

	/** File size snapshot at discovery time (safeguard hint; apply re-verifies live). */
	Long getSize();

	DedupGroupMember setSize(Long size);

	/** Completeness snapshot at discovery time; 0 means complete. */
	Long getZeroChunkCount();

	DedupGroupMember setZeroChunkCount(Long zeroChunkCount);
}
