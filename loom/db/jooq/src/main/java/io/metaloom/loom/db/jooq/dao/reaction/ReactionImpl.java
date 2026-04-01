package io.metaloom.loom.db.jooq.dao.reaction;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.vertx.core.json.JsonObject;

public class ReactionImpl extends AbstractEditableElement<Reaction> implements Reaction {

	private String type;

	private Integer rating;

	private UUID assetUuid;

	private UUID annotationUuid;

	private UUID commentUuid;

	private UUID taskUuid;

	private JsonObject meta;

	@Override
	public String getType() {
		return type;
	}

	@Override
	public ReactionImpl setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Reaction setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getCommentUuid() {
		return commentUuid;
	}

	@Override
	public Reaction setCommentUuid(UUID commentUuid) {
		this.commentUuid = commentUuid;
		return this;
	}

	@Override
	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	@Override
	public Reaction setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}

	@Override
	public Integer getRating() {
		return rating;
	}

	@Override
	public Reaction setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public Reaction setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public Reaction setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}
}
