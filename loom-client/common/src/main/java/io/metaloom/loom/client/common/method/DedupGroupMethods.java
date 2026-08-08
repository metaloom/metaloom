package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupCreateRequest;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupUpdateRequest;

/**
 * Client access to the dedup review routes (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md §2.1).
 *
 * <p>
 * The discovery node calls {@link #createDedupGroup}; a reviewer confirms/denies via {@link #updateDedupGroup}; the apply node reads an asset's
 * confirmed groups via {@link #listAssetDedupGroups}.
 * </p>
 */
public interface DedupGroupMethods {

	/**
	 * Create (upsert) a candidate duplicate group. Used by the discovery node.
	 *
	 * <p>
	 * Answers <b>201</b> for a new proposal and <b>200</b> when the same candidate set was already confirmed or rejected - in which case nothing is
	 * written and the existing decision is returned. Check the returned status before reporting a discovery.
	 * </p>
	 */
	LoomClientRequest<DedupGroupResponse> createDedupGroup(DedupGroupCreateRequest request);

	/**
	 * List review groups, optionally filtered by status ({@code PENDING}/{@code CONFIRMED}/{@code REJECTED}); pass {@code null} for all.
	 *
	 * @param from
	 *            keyset cursor - the {@code _metainfo.lastUuid} of the previous page, or {@code null} for the first page
	 * @param limit
	 *            page size, or {@code null} for the server default (25)
	 */
	LoomClientRequest<DedupGroupListResponse> listDedupGroups(String status, UUID from, Integer limit);

	LoomClientRequest<DedupGroupResponse> loadDedupGroup(UUID uuid);

	/** Confirm/deny a group. */
	LoomClientRequest<DedupGroupResponse> updateDedupGroup(UUID uuid, DedupGroupUpdateRequest request);

	LoomClientRequest<NoResponse> deleteDedupGroup(UUID uuid);

	/** Groups that involve one asset (as KEEP or DUP). The apply node filters these to CONFIRMED. */
	LoomClientRequest<DedupGroupListResponse> listAssetDedupGroups(UUID assetUuid);
}
