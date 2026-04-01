package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_COMMENT;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentModel;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class CommentEndpointService extends AbstractCRUDEndpointService<CommentDao, Comment> {

	@Inject
	public CommentEndpointService(CommentDao commentDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(commentDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_COMMENT, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_COMMENT, modelBuilder::toCommentList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_COMMENT, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_COMMENT, () -> {
			CommentCreateRequest request = lrc.requestBody(CommentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			String text = request.getText();
			Comment comment = dao().createComment(userUuid, title, text);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_COMMENT, () -> {
			CommentUpdateRequest request = lrc.requestBody(CommentUpdateRequest.class);
			validator.validate(request);

			Comment comment = dao().load(uuid);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse);
	}

	private void update(CommentModel<?> model, Comment comment) {
		update(model::getTitle, comment::setTitle);
		update(model::getText, comment::setText);
		update(model::getMeta, comment::setMeta);
	}
}
