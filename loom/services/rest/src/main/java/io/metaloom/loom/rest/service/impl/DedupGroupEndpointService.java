package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.READ_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_DEDUP;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.dedup.DedupGroup;
import io.metaloom.loom.db.model.dedup.DedupGroupDao;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.dedup.DedupGroupCreateRequest;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupUpdateRequest;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Serves the deduplication review routes (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md §2.1).
 *
 * <p>
 * The discovery node posts candidate groups (PENDING); a human confirms or rejects them; the apply node reads back only the CONFIRMED ones. The two
 * halves are deliberately separate permissions so a reviewer can curate duplicates without being able to mutate assets.
 * </p>
 */
@Singleton
public class DedupGroupEndpointService extends AbstractEndpointService {

	private final DedupGroupDao dedupDao;

	@Inject
	public DedupGroupEndpointService(DedupGroupDao dedupDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.dedupDao = dedupDao;
	}

	/**
	 * {@code POST /api/v1/dedup-groups} - create or update a candidate group.
	 *
	 * <p>
	 * <b>Idempotent by design.</b> Re-running discovery over unchanged content must not pile up duplicate review records, so a PENDING group with the
	 * same KEEP asset and algorithm is rewritten in place. A group a human has already decided on (CONFIRMED/REJECTED) is never touched - re-discovery
	 * must not silently reopen a settled decision.
	 * </p>
	 */
	public void createDedupGroup(LoomRoutingContext lrc) {
		checkPerm(lrc, CREATE_DEDUP, () -> {
			DedupGroupCreateRequest request = lrc.requestBody(DedupGroupCreateRequest.class);

			if (request.getAlgorithm() == null || request.getAlgorithm().isBlank()) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The algorithm must be set.");
			}
			if (request.getMembers() == null || request.getMembers().isEmpty()) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A dedup group needs at least one member.");
			}
			UUID keepUuid = parseUuid(request.getKeepAssetUuid(), "keepAssetUuid");

			UUID userUuid = lrc.userUuid();
			DedupGroup existing = keepUuid == null ? null : dedupDao.findPendingByKeep(keepUuid, request.getAlgorithm());
			if (existing != null) {
				// Replace the candidate set of the still-pending group rather than creating a second one for the same content.
				dedupDao.deleteGroup(existing.getUuid());
			}

			DedupGroup group = dedupDao.createGroup(userUuid, request.getAlgorithm());
			group.setKeepAssetUuid(keepUuid);
			group.setScore(request.getScore());
			dedupDao.storeGroup(group);

			for (DedupGroupMemberModel member : request.getMembers()) {
				UUID memberAsset = parseUuid(member.getAssetUuid(), "members.assetUuid");
				if (memberAsset == null) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every dedup group member needs an assetUuid.");
				}
				String role = member.getRole();
				if (!DedupGroupMember.ROLE_KEEP.equals(role) && !DedupGroupMember.ROLE_DUP.equals(role)) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
						"The member role must be " + DedupGroupMember.ROLE_KEEP + " or " + DedupGroupMember.ROLE_DUP + ".");
				}
				dedupDao.addMember(group.getUuid(), memberAsset, role, member.getScore(), member.getSize(), member.getZeroChunkCount());
			}

			lrc.send(toResponse(group), 201);
		});
	}

	/** {@code GET /api/v1/dedup-groups?status=PENDING} - the review queue. */
	public void listDedupGroups(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_DEDUP, () -> {
			List<String> statuses = lrc.queryParam("status");
			String status = statuses == null || statuses.isEmpty() ? null : statuses.get(0);

			List<DedupGroup> groups;
			if (status == null || status.isBlank()) {
				// No status filter: the whole review history, newest first.
				groups = new java.util.ArrayList<>();
				groups.addAll(dedupDao.listByStatus(DedupGroup.STATUS_PENDING));
				groups.addAll(dedupDao.listByStatus(DedupGroup.STATUS_CONFIRMED));
				groups.addAll(dedupDao.listByStatus(DedupGroup.STATUS_REJECTED));
			} else {
				groups = dedupDao.listByStatus(requireStatus(status));
			}

			DedupGroupListResponse response = new DedupGroupListResponse();
			groups.forEach(group -> response.add(toResponse(group)));
			lrc.send(response);
		});
	}

	/** {@code GET /api/v1/dedup-groups/:uuid}. */
	public void loadDedupGroup(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, READ_DEDUP, () -> {
			lrc.send(toResponse(requireGroup(uuid)));
		});
	}

	/**
	 * {@code PATCH /api/v1/dedup-groups/:uuid} - confirm or reject.
	 *
	 * <p>
	 * This is the human-in-the-loop step: only a CONFIRMED group is ever acted on by the apply node.
	 * </p>
	 */
	public void updateDedupGroup(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, UPDATE_DEDUP, () -> {
			DedupGroupUpdateRequest request = lrc.requestBody(DedupGroupUpdateRequest.class);
			requireGroup(uuid);

			String status = requireStatus(request.getStatus());
			UUID keepUuid = parseUuid(request.getKeepAssetUuid(), "keepAssetUuid");
			DedupGroup updated = dedupDao.updateStatus(uuid, status, keepUuid, lrc.userUuid());
			lrc.send(toResponse(updated));
		});
	}

	/** {@code DELETE /api/v1/dedup-groups/:uuid} - members cascade. */
	public void deleteDedupGroup(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, DELETE_DEDUP, () -> {
			requireGroup(uuid);
			dedupDao.deleteGroup(uuid);
			lrc.sendNoContent();
		});
	}

	/** {@code GET /api/v1/assets/:uuid/dedup-groups} - groups involving one asset; the apply node's entry point. */
	public void listAssetDedupGroups(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_DEDUP, () -> {
			DedupGroupListResponse response = new DedupGroupListResponse();
			dedupDao.listByAsset(assetUuid).forEach(group -> response.add(toResponse(group)));
			lrc.send(response);
		});
	}

	// ---------------------------------------------------------------------------------------------

	private DedupGroup requireGroup(UUID uuid) {
		DedupGroup group = dedupDao.loadGroup(uuid);
		if (group == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Dedup group not found.");
		}
		return group;
	}

	private static String requireStatus(String status) {
		if (DedupGroup.STATUS_PENDING.equals(status)
			|| DedupGroup.STATUS_CONFIRMED.equals(status)
			|| DedupGroup.STATUS_REJECTED.equals(status)) {
			return status;
		}
		throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The status must be one of PENDING, CONFIRMED, REJECTED.");
	}

	private static UUID parseUuid(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' is not a valid uuid.");
		}
	}

	private DedupGroupResponse toResponse(DedupGroup group) {
		DedupGroupResponse response = new DedupGroupResponse()
			.setUuid(group.getUuid() == null ? null : group.getUuid().toString())
			.setAlgorithm(group.getAlgorithm())
			.setStatus(group.getStatus())
			.setKeepAssetUuid(group.getKeepAssetUuid() == null ? null : group.getKeepAssetUuid().toString())
			.setScore(group.getScore());

		List<DedupGroupMemberModel> members = new java.util.ArrayList<>();
		for (DedupGroupMember member : dedupDao.loadMembers(group.getUuid())) {
			members.add(new DedupGroupMemberModel()
				.setAssetUuid(member.getAssetUuid() == null ? null : member.getAssetUuid().toString())
				.setRole(member.getRole())
				.setScore(member.getScore())
				.setSize(member.getSize())
				.setZeroChunkCount(member.getZeroChunkCount()));
		}
		response.setMembers(members);
		return response;
	}
}
