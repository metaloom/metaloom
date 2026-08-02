package io.metaloom.loom.db.jooq.dao.comment;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.comment.Comment;

public class CommentImpl extends AbstractEditableElement<Comment> implements Comment {

	private String title;

	private String text;

	private UUID taskUuid;

	private UUID assetUuid;

	private UUID annotationUuid;

	private UUID parentUuid;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Comment setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public Comment setText(String text) {
		this.text = text;
		return this;
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public Comment setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Comment setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	@Override
	public Comment setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}

	@Override
	public UUID getParentUuid() {
		return parentUuid;
	}

	@Override
	public Comment setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

}
