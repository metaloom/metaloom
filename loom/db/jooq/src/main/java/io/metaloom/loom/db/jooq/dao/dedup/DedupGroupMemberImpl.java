package io.metaloom.loom.db.jooq.dao.dedup;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractElement;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;

public class DedupGroupMemberImpl extends AbstractElement<DedupGroupMember> implements DedupGroupMember {

	private UUID uuid;
	private UUID groupUuid;
	private UUID assetUuid;
	private String role;
	private Float score;
	private Long size;
	private Long zeroChunkCount;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public DedupGroupMember setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getGroupUuid() {
		return groupUuid;
	}

	@Override
	public DedupGroupMember setGroupUuid(UUID groupUuid) {
		this.groupUuid = groupUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public DedupGroupMember setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getRole() {
		return role;
	}

	@Override
	public DedupGroupMember setRole(String role) {
		this.role = role;
		return this;
	}

	@Override
	public Float getScore() {
		return score;
	}

	@Override
	public DedupGroupMember setScore(Float score) {
		this.score = score;
		return this;
	}

	@Override
	public Long getSize() {
		return size;
	}

	@Override
	public DedupGroupMember setSize(Long size) {
		this.size = size;
		return this;
	}

	@Override
	public Long getZeroChunkCount() {
		return zeroChunkCount;
	}

	@Override
	public DedupGroupMember setZeroChunkCount(Long zeroChunkCount) {
		this.zeroChunkCount = zeroChunkCount;
		return this;
	}
}
