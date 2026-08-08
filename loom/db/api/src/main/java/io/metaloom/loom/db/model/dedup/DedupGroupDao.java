package io.metaloom.loom.db.model.dedup;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;
import io.metaloom.loom.db.page.Page;

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

	/**
	 * One page of the review queue, newest first.
	 *
	 * <p>
	 * Deliberately bespoke rather than the generic {@code CRUDDao.loadPage}: this DAO is a plain {@link Dao}, and {@code AbstractJooqDao.getField}
	 * casts every sort column to {@code Field<UUID>}, so ordering by {@code created} through the generic path throws. Ordering is
	 * {@code (created DESC, uuid DESC)} - the uuid tie-break keeps a burst of proposals written inside one millisecond paging deterministically.
	 * </p>
	 *
	 * @param status
	 *            restrict to one review status, or {@code null} for the whole history
	 * @param fromId
	 *            seek cursor - the uuid of the last element of the previous page, or {@code null} for the first page
	 */
	Page<DedupGroup> loadPage(String status, UUID fromId, int pageSize);

	/** All groups that involve the given asset (as KEEP or DUP member). */
	List<DedupGroup> listByAsset(UUID assetUuid);

	/**
	 * The existing PENDING group with the given KEEP asset and algorithm, or {@code null}. Lets the discovery node upsert idempotently instead of
	 * creating a duplicate review record on every re-run.
	 */
	DedupGroup findPendingByKeep(UUID keepAssetUuid, String algorithm);

	/**
	 * Groups that are no longer PENDING (CONFIRMED or REJECTED), produced by the given algorithm, and involving at least one of the given assets.
	 *
	 * <p>
	 * This is the input to the "never re-propose a decided candidate set" guard: {@link #findPendingByKeep(UUID, String)} only collapses repeated
	 * PENDING proposals, so without this a pair a reviewer already rejected returns to the queue on every discovery run. The caller compares member
	 * sets to decide whether a proposal really is the same one — a *new* duplicate of an already-reviewed asset must still get through.
	 * </p>
	 */
	List<DedupGroup> listDecidedByAssets(Collection<UUID> assetUuids, String algorithm);

	/** Set status (and optionally the KEEP asset) and record the editor. Returns the reloaded group. */
	DedupGroup updateStatus(UUID uuid, String status, UUID keepAssetUuid, UUID editorUuid);

	void deleteGroup(UUID uuid);

	/** Add a member to a group and return it with its assigned uuid. */
	DedupGroupMember addMember(UUID groupUuid, UUID assetUuid, String role, Float score, Long size, Long zeroChunkCount);

	List<DedupGroupMember> loadMembers(UUID groupUuid);
}
