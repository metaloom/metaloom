package io.metaloom.loom.db.jooq.dao.dedup;

import static io.metaloom.loom.db.jooq.tables.JooqDedupGroup.DEDUP_GROUP;
import static io.metaloom.loom.db.jooq.tables.JooqDedupGroupMember.DEDUP_GROUP_MEMBER;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.UpdateSetMoreStep;

import io.metaloom.loom.db.jooq.tables.records.JooqDedupGroupRecord;

import io.metaloom.loom.db.jooq.enums.JooqDedupStatus;
import io.metaloom.loom.db.model.dedup.DedupGroup;
import io.metaloom.loom.db.model.dedup.DedupGroupDao;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;

/**
 * jOOQ implementation of the deduplication review model. Manages both the {@code dedup_group} parent and its {@code dedup_group_member} children.
 */
@Singleton
public class DedupGroupDaoImpl implements DedupGroupDao {

	private final DSLContext ctx;

	@Inject
	public DedupGroupDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "DedupGroup";
	}

	@Override
	public void clear() {
		// Members cascade on group delete, but clear both explicitly so a partial state cannot linger.
		ctx.deleteFrom(DEDUP_GROUP_MEMBER).execute();
		ctx.deleteFrom(DEDUP_GROUP).execute();
	}

	@Override
	public long count() {
		return ctx.fetchCount(DEDUP_GROUP);
	}

	@Override
	public DedupGroup createGroup(UUID creatorUuid, String algorithm) {
		DedupGroupImpl group = new DedupGroupImpl();
		group.setAlgorithm(algorithm);
		group.setStatus(DedupGroup.STATUS_PENDING);
		group.setCreatorUuid(creatorUuid);
		group.setEditorUuid(creatorUuid);
		return group;
	}

	@Override
	public DedupGroup storeGroup(DedupGroup group) {
		UUID uuid = ctx.insertInto(DEDUP_GROUP)
			.set(DEDUP_GROUP.ALGORITHM, group.getAlgorithm())
			.set(DEDUP_GROUP.STATUS, status(group.getStatus()))
			.set(DEDUP_GROUP.KEEP_ASSET_UUID, group.getKeepAssetUuid())
			.set(DEDUP_GROUP.SCORE, group.getScore())
			.set(DEDUP_GROUP.META, group.getMeta())
			.set(DEDUP_GROUP.CREATOR_UUID, group.getCreatorUuid())
			.set(DEDUP_GROUP.EDITOR_UUID, group.getEditorUuid())
			.returning(DEDUP_GROUP.UUID)
			.fetchOne(DEDUP_GROUP.UUID);
		group.setUuid(uuid);
		return group;
	}

	@Override
	public DedupGroup loadGroup(UUID uuid) {
		return ctx.selectFrom(DEDUP_GROUP)
			.where(DEDUP_GROUP.UUID.eq(uuid))
			.fetchOne(this::mapGroup);
	}

	@Override
	public List<DedupGroup> listByStatus(String status) {
		return ctx.selectFrom(DEDUP_GROUP)
			.where(DEDUP_GROUP.STATUS.eq(status(status)))
			.orderBy(DEDUP_GROUP.CREATED.desc())
			.fetch(this::mapGroup);
	}

	@Override
	public List<DedupGroup> listByAsset(UUID assetUuid) {
		return ctx.selectDistinct(DEDUP_GROUP.fields())
			.from(DEDUP_GROUP)
			.join(DEDUP_GROUP_MEMBER).on(DEDUP_GROUP_MEMBER.GROUP_UUID.eq(DEDUP_GROUP.UUID))
			.where(DEDUP_GROUP_MEMBER.ASSET_UUID.eq(assetUuid))
			.orderBy(DEDUP_GROUP.CREATED.desc())
			.fetch(this::mapGroup);
	}

	@Override
	public DedupGroup findPendingByKeep(UUID keepAssetUuid, String algorithm) {
		return ctx.selectFrom(DEDUP_GROUP)
			.where(DEDUP_GROUP.KEEP_ASSET_UUID.eq(keepAssetUuid)
				.and(DEDUP_GROUP.ALGORITHM.eq(algorithm))
				.and(DEDUP_GROUP.STATUS.eq(JooqDedupStatus.PENDING)))
			.fetchAny(this::mapGroup);
	}

	@Override
	public DedupGroup updateStatus(UUID uuid, String status, UUID keepAssetUuid, UUID editorUuid) {
		UpdateSetMoreStep<JooqDedupGroupRecord> step = ctx.update(DEDUP_GROUP)
			.set(DEDUP_GROUP.STATUS, status(status))
			.set(DEDUP_GROUP.EDITED, LocalDateTime.now(ZoneOffset.UTC))
			.set(DEDUP_GROUP.EDITOR_UUID, editorUuid);
		if (keepAssetUuid != null) {
			step = step.set(DEDUP_GROUP.KEEP_ASSET_UUID, keepAssetUuid);
		}
		step.where(DEDUP_GROUP.UUID.eq(uuid)).execute();
		return loadGroup(uuid);
	}

	@Override
	public void deleteGroup(UUID uuid) {
		ctx.deleteFrom(DEDUP_GROUP).where(DEDUP_GROUP.UUID.eq(uuid)).execute();
	}

	@Override
	public DedupGroupMember addMember(UUID groupUuid, UUID assetUuid, String role, Float score, Long size, Long zeroChunkCount) {
		UUID uuid = ctx.insertInto(DEDUP_GROUP_MEMBER)
			.set(DEDUP_GROUP_MEMBER.GROUP_UUID, groupUuid)
			.set(DEDUP_GROUP_MEMBER.ASSET_UUID, assetUuid)
			.set(DEDUP_GROUP_MEMBER.ROLE, role)
			.set(DEDUP_GROUP_MEMBER.SCORE, score)
			.set(DEDUP_GROUP_MEMBER.SIZE, size)
			.set(DEDUP_GROUP_MEMBER.ZERO_CHUNK_COUNT, zeroChunkCount)
			.returning(DEDUP_GROUP_MEMBER.UUID)
			.fetchOne(DEDUP_GROUP_MEMBER.UUID);
		DedupGroupMemberImpl member = new DedupGroupMemberImpl();
		member.setUuid(uuid);
		member.setGroupUuid(groupUuid);
		member.setAssetUuid(assetUuid);
		member.setRole(role);
		member.setScore(score);
		member.setSize(size);
		member.setZeroChunkCount(zeroChunkCount);
		return member;
	}

	@Override
	public List<DedupGroupMember> loadMembers(UUID groupUuid) {
		return ctx.selectFrom(DEDUP_GROUP_MEMBER)
			.where(DEDUP_GROUP_MEMBER.GROUP_UUID.eq(groupUuid))
			.fetch(this::mapMember);
	}

	private static JooqDedupStatus status(String status) {
		return status == null ? JooqDedupStatus.PENDING : JooqDedupStatus.lookupLiteral(status);
	}

	private DedupGroup mapGroup(Record r) {
		DedupGroupImpl group = new DedupGroupImpl();
		group.setUuid(r.get(DEDUP_GROUP.UUID));
		group.setAlgorithm(r.get(DEDUP_GROUP.ALGORITHM));
		JooqDedupStatus st = r.get(DEDUP_GROUP.STATUS);
		group.setStatus(st == null ? null : st.getLiteral());
		group.setKeepAssetUuid(r.get(DEDUP_GROUP.KEEP_ASSET_UUID));
		group.setScore(r.get(DEDUP_GROUP.SCORE));
		group.setMeta(r.get(DEDUP_GROUP.META));
		group.setCreatorUuid(r.get(DEDUP_GROUP.CREATOR_UUID));
		group.setEditorUuid(r.get(DEDUP_GROUP.EDITOR_UUID));
		group.setCreated(toInstant(r.get(DEDUP_GROUP.CREATED)));
		group.setEdited(toInstant(r.get(DEDUP_GROUP.EDITED)));
		return group;
	}

	private DedupGroupMember mapMember(Record r) {
		DedupGroupMemberImpl member = new DedupGroupMemberImpl();
		member.setUuid(r.get(DEDUP_GROUP_MEMBER.UUID));
		member.setGroupUuid(r.get(DEDUP_GROUP_MEMBER.GROUP_UUID));
		member.setAssetUuid(r.get(DEDUP_GROUP_MEMBER.ASSET_UUID));
		member.setRole(r.get(DEDUP_GROUP_MEMBER.ROLE));
		member.setScore(r.get(DEDUP_GROUP_MEMBER.SCORE));
		member.setSize(r.get(DEDUP_GROUP_MEMBER.SIZE));
		member.setZeroChunkCount(r.get(DEDUP_GROUP_MEMBER.ZERO_CHUNK_COUNT));
		return member;
	}

	private static Instant toInstant(LocalDateTime ldt) {
		return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
	}
}
