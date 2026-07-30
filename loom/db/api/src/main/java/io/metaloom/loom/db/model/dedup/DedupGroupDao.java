package io.metaloom.loom.db.model.dedup;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;

/**
 * DAO for the deduplication review model (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md).
 *
 * <p>
 * The discovery node creates PENDING groups; a reviewer confirms/denies via {@link #updateStatus}; the apply node reads CONFIRMED groups for an asset
 * via {@link #listByAsset}. Deleting a group cascades to its members (FK); deleting an asset cascades its memberships and nulls the group's
 * {@code keep_asset_uuid}.
 * </p>
 */
public interface DedupGroupDao extends Dao {

	/** Create a transient group (not persisted until {@link #storeGroup(DedupGroup)}). */
	DedupGroup createGroup(UUID creatorUuid, String algorithm);

	/** Insert the group and return it with its assigned uuid. */
	DedupGroup storeGroup(DedupGroup group);

	DedupGroup loadGroup(UUID uuid);

	/** All groups in a given status, newest first. */
	List<DedupGroup> listByStatus(String status);

	/** All groups that involve the given asset (as KEEP or DUP member). */
	List<DedupGroup> listByAsset(UUID assetUuid);

	/**
	 * The existing PENDING group with the given KEEP asset and algorithm, or {@code null}. Lets the discovery node upsert idempotently instead of
	 * creating a duplicate review record on every re-run.
	 */
	DedupGroup findPendingByKeep(UUID keepAssetUuid, String algorithm);

	/** Set status (and optionally the KEEP asset) and record the editor. Returns the reloaded group. */
	DedupGroup updateStatus(UUID uuid, String status, UUID keepAssetUuid, UUID editorUuid);

	void deleteGroup(UUID uuid);

	/** Add a member to a group and return it with its assigned uuid. */
	DedupGroupMember addMember(UUID groupUuid, UUID assetUuid, String role, Float score, Long size, Long zeroChunkCount);

	List<DedupGroupMember> loadMembers(UUID groupUuid);
}
