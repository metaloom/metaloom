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

	/** Create (upsert) a candidate duplicate group. Used by the discovery node. */
	LoomClientRequest<DedupGroupResponse> createDedupGroup(DedupGroupCreateRequest request);

	/** List review groups, optionally filtered by status ({@code PENDING}/{@code CONFIRMED}/{@code REJECTED}); pass {@code null} for all. */
	LoomClientRequest<DedupGroupListResponse> listDedupGroups(String status);

	LoomClientRequest<DedupGroupResponse> loadDedupGroup(UUID uuid);

	/** Confirm/deny a group. */
	LoomClientRequest<DedupGroupResponse> updateDedupGroup(UUID uuid, DedupGroupUpdateRequest request);

	LoomClientRequest<NoResponse> deleteDedupGroup(UUID uuid);

	/** Groups that involve one asset (as KEEP or DUP). The apply node filters these to CONFIRMED. */
	LoomClientRequest<DedupGroupListResponse> listAssetDedupGroups(UUID assetUuid);
}
