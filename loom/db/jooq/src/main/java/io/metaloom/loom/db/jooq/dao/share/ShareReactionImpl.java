package io.metaloom.loom.db.jooq.dao.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractElement;
import io.metaloom.loom.db.model.share.ShareReaction;

public class ShareReactionImpl extends AbstractElement<ShareReaction> implements ShareReaction {

	private UUID uuid;
	private UUID shareUuid;
	private UUID assetUuid;
	private UUID shareCommentUuid;
	private UUID shareAnnotationUuid;
	private String type;
	private String authorName;
	private Instant created;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public ShareReaction setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getShareUuid() {
		return shareUuid;
	}

	@Override
	public ShareReaction setShareUuid(UUID shareUuid) {
		this.shareUuid = shareUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public ShareReaction setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getShareCommentUuid() {
		return shareCommentUuid;
	}

	@Override
	public ShareReaction setShareCommentUuid(UUID shareCommentUuid) {
		this.shareCommentUuid = shareCommentUuid;
		return this;
	}

	@Override
	public UUID getShareAnnotationUuid() {
		return shareAnnotationUuid;
	}

	@Override
	public ShareReaction setShareAnnotationUuid(UUID shareAnnotationUuid) {
		this.shareAnnotationUuid = shareAnnotationUuid;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public ShareReaction setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public String getAuthorName() {
		return authorName;
	}

	@Override
	public ShareReaction setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public ShareReaction setCreated(Instant created) {
		this.created = created;
		return this;
	}
}
