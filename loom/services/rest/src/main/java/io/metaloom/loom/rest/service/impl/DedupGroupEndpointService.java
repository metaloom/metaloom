package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.READ_DEDUP;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_DEDUP;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.dedup.DedupGroup;
import io.metaloom.loom.db.model.dedup.DedupGroupDao;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.parameter.PagingParameters;
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
	 *
	 * <p>
	 * <b>A decided candidate set is never re-proposed either.</b> The PENDING upsert alone is not enough: a pair a reviewer rejected would come back as
	 * a brand new PENDING group on the next discovery run, refilling the queue with decisions already made. When the exact same member set was already
	 * decided for this algorithm, nothing is written and the decided group is returned with <b>200</b> instead of 201, so a client can tell a no-op from
	 * a fresh proposal without treating it as an error. The comparison is deliberately on the whole member set rather than "this asset appears in some
	 * decided group" - a genuinely new duplicate of an already-reviewed file must still reach a reviewer.
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
			UUID keepUuid = optionalUuid(request.getKeepAssetUuid(), "keepAssetUuid");
			String algorithm = request.getAlgorithm();

			// Validate every member before writing anything - a bad role halfway through would otherwise leave a half-populated group behind.
			List<MemberEntry> members = new java.util.ArrayList<>();
			for (DedupGroupMemberModel member : request.getMembers()) {
				UUID memberAsset = optionalUuid(member.getAssetUuid(), "members.assetUuid");
				if (memberAsset == null) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every dedup group member needs an assetUuid.");
				}
				String role = member.getRole();
				if (!DedupGroupMember.ROLE_KEEP.equals(role) && !DedupGroupMember.ROLE_DUP.equals(role)) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
						"The member role must be " + DedupGroupMember.ROLE_KEEP + " or " + DedupGroupMember.ROLE_DUP + ".");
				}
				members.add(new MemberEntry(memberAsset, role, member.getScore(), member.getSize(), member.getZeroChunkCount()));
			}

			DedupGroup decided = findDecidedWithSameMembers(members, algorithm);
			if (decided != null) {
				lrc.send(toResponse(decided), 200);
				return;
			}

			UUID userUuid = lrc.userUuid();
			DedupGroup existing = keepUuid == null ? null : dedupDao.findPendingByKeep(keepUuid, algorithm);
			if (existing != null) {
				// Replace the candidate set of the still-pending group rather than creating a second one for the same content.
				dedupDao.deleteGroup(existing.getUuid());
			}

			DedupGroup group = dedupDao.createGroup(userUuid, algorithm);
			group.setKeepAssetUuid(keepUuid);
			group.setScore(request.getScore());
			dedupDao.storeGroup(group);

			for (MemberEntry member : members) {
				dedupDao.addMember(group.getUuid(), member.assetUuid(), member.role(), member.score(), member.size(), member.zeroChunkCount());
			}

			lrc.send(toResponse(group), 201);
		});
	}

	/**
	 * {@code GET /api/v1/dedup-groups?status=PENDING} - the review queue, keyset paged.
	 *
	 * <p>
	 * Without a {@code status} the whole review history is returned in one ordering rather than three concatenated status lists. Paging is the standard
	 * {@code ?limit=}/{@code ?from=} contract, so a queue of any size is safe to open.
	 * </p>
	 */
	public void listDedupGroups(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_DEDUP, () -> {
			List<String> statuses = lrc.queryParam("status");
			String raw = statuses == null || statuses.isEmpty() ? null : statuses.get(0);
			String status = raw == null || raw.isBlank() ? null : requireStatus(raw);

			// pagingParams(), never lrc.pageSize() - the latter reads a *path* parameter and would ignore ?limit= entirely.
			PagingParameters paging = lrc.pagingParams();
			Page<DedupGroup> page = dedupDao.loadPage(status, paging.from(), paging.limit());

			lrc.send(modelBuilder.setPage(new DedupGroupListResponse(), page, this::toResponse));
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
			UUID keepUuid = optionalUuid(request.getKeepAssetUuid(), "keepAssetUuid");
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

	/** A validated member of an incoming create request, held until the whole request is known to be well-formed. */
	private record MemberEntry(UUID assetUuid, String role, Float score, Long size, Long zeroChunkCount) {
	}

	/**
	 * The already-decided group covering exactly this candidate set for this algorithm, or {@code null}.
	 *
	 * <p>
	 * Roles are deliberately ignored: after a reviewer reassigns the KEEP, the same two files can come back with their roles swapped, and that is still
	 * the same decision. Only the set of participating assets identifies a candidate set.
	 * </p>
	 */
	private DedupGroup findDecidedWithSameMembers(List<MemberEntry> members, String algorithm) {
		Set<UUID> wanted = members.stream().map(MemberEntry::assetUuid).collect(Collectors.toSet());
		for (DedupGroup candidate : dedupDao.listDecidedByAssets(wanted, algorithm)) {
			Set<UUID> present = dedupDao.loadMembers(candidate.getUuid()).stream()
				.map(DedupGroupMember::getAssetUuid)
				.collect(Collectors.toSet());
			if (wanted.equals(present)) {
				return candidate;
			}
		}
		return null;
	}

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
