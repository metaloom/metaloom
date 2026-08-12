package io.metaloom.loom.db.jooq.dao.remix;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractElement;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.model.remix.RemixRole;

public class RemixMemberImpl extends AbstractElement<RemixMember> implements RemixMember {

	private UUID uuid;

	private UUID remixUuid;

	private UUID assetUuid;

	private RemixRole role;

	private Integer ordinal;

	private Instant created;

	private UUID creatorUuid;

	private String filename;

	private String mimeType;

	private String sha512sum;

	private Long size;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public RemixMember setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getRemixUuid() {
		return remixUuid;
	}

	@Override
	public RemixMember setRemixUuid(UUID remixUuid) {
		this.remixUuid = remixUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public RemixMember setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public RemixRole getRole() {
		return role;
	}

	@Override
	public RemixMember setRole(RemixRole role) {
		this.role = role;
		return this;
	}

	@Override
	public Integer getOrdinal() {
		return ordinal;
	}

	@Override
	public RemixMember setOrdinal(Integer ordinal) {
		this.ordinal = ordinal;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public RemixMember setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public UUID getCreatorUuid() {
		return creatorUuid;
	}

	@Override
	public RemixMember setCreatorUuid(UUID creatorUuid) {
		this.creatorUuid = creatorUuid;
		return this;
	}

	@Override
	public String getFilename() {
		return filename;
	}

	@Override
	public RemixMember setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public RemixMember setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public String getSha512sum() {
		return sha512sum;
	}

	@Override
	public RemixMember setSha512sum(String sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}

	@Override
	public Long getSize() {
		return size;
	}

	@Override
	public RemixMember setSize(Long size) {
		this.size = size;
		return this;
	}

	@Override
	public String toString() {
		return "[RemixMember] uuid: " + uuid + ", asset: " + assetUuid + ", role: " + role;
	}
}
