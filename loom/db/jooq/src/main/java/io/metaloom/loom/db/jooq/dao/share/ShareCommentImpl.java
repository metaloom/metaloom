package io.metaloom.loom.db.jooq.dao.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractElement;
import io.metaloom.loom.db.model.share.ShareComment;

public class ShareCommentImpl extends AbstractElement<ShareComment> implements ShareComment {

	private UUID uuid;
	private UUID shareUuid;
	private UUID assetUuid;
	private UUID parentUuid;
	private UUID shareAnnotationUuid;
	private String text;
	private String authorName;
	private Instant created;
	private Instant edited;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public ShareComment setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getShareUuid() {
		return shareUuid;
	}

	@Override
	public ShareComment setShareUuid(UUID shareUuid) {
		this.shareUuid = shareUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public ShareComment setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getParentUuid() {
		return parentUuid;
	}

	@Override
	public ShareComment setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

	@Override
	public UUID getShareAnnotationUuid() {
		return shareAnnotationUuid;
	}

	@Override
	public ShareComment setShareAnnotationUuid(UUID shareAnnotationUuid) {
		this.shareAnnotationUuid = shareAnnotationUuid;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public ShareComment setText(String text) {
		this.text = text;
		return this;
	}

	@Override
	public String getAuthorName() {
		return authorName;
	}

	@Override
	public ShareComment setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public ShareComment setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public Instant getEdited() {
		return edited;
	}

	@Override
	public ShareComment setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}
}
